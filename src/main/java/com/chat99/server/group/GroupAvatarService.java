package com.chat99.server.group;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.oss.OssProperties;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupAvatarService {

    private static final Logger log = LoggerFactory.getLogger(GroupAvatarService.class);
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final ExecutorService OSS_UPLOAD_EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "group-avatar-oss");
        t.setDaemon(true);
        return t;
    });

    private final OssClient oss;
    private final OssProperties props;
    private final ImageProcessor processor;

    public GroupAvatarService(OssClient oss, OssProperties props, ImageProcessor processor) {
        this.oss = oss;
        this.props = props;
        this.processor = processor;
    }

    public record UploadResult(String originUrl, String previewUrl, String thumbUrl) {}

    public UploadResult uploadAvatar(MultipartFile file, String objectKeyBase) throws IOException {
        log.info("uploadAvatar enter name={} contentType={} size={}",
            file.getOriginalFilename(), file.getContentType(), file.getSize());
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        if (file.getSize() > props.maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        String contentType = file.getContentType();
        boolean typeOk = contentType != null && ALLOWED_TYPES.contains(contentType.toLowerCase());
        if (!typeOk) {
            String filename = file.getOriginalFilename();
            boolean ambiguousType = contentType == null
                || contentType.equalsIgnoreCase("application/octet-stream");
            if (ambiguousType && filename != null) {
                int dot = filename.lastIndexOf('.');
                if (dot >= 0) {
                    String ext = filename.substring(dot + 1).toLowerCase();
                    if (ALLOWED_EXTENSIONS.contains(ext)) {
                        typeOk = true;
                    }
                }
            }
        }
        if (!typeOk) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }

        byte[] raw = file.getBytes();
        BufferedImage img;
        try {
            img = processor.decode(raw);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE");
        }

        int q = props.jpegQuality();
        byte[] originJpeg = processor.toJpeg(img, q);
        byte[] previewJpeg = processor.resizeKeepAspect(img, props.previewLongEdge(), q);
        byte[] thumbJpeg = processor.cropCenterSquare(img, props.thumbSize(), q);

        String originKey = objectKeyBase + "_origin.jpg";
        String previewKey = objectKeyBase + "_preview.jpg";
        String thumbKey = objectKeyBase + "_thumb.jpg";

        CompletableFuture<String> originFuture = CompletableFuture.supplyAsync(
            () -> oss.putBytes(originKey, originJpeg, "image/jpeg"), OSS_UPLOAD_EXECUTOR);
        CompletableFuture<String> previewFuture = CompletableFuture.supplyAsync(
            () -> oss.putBytes(previewKey, previewJpeg, "image/jpeg"), OSS_UPLOAD_EXECUTOR);
        CompletableFuture<String> thumbFuture = CompletableFuture.supplyAsync(
            () -> oss.putBytes(thumbKey, thumbJpeg, "image/jpeg"), OSS_UPLOAD_EXECUTOR);

        try {
            CompletableFuture.allOf(originFuture, previewFuture, thumbFuture).join();
            String originUrl = originFuture.join();
            String previewUrl = previewFuture.join();
            String thumbUrl = thumbFuture.join();
            log.info("avatar uploaded base={} origin={} preview={} thumb={} bytes",
                objectKeyBase, originJpeg.length, previewJpeg.length, thumbJpeg.length);
            return new UploadResult(originUrl, previewUrl, thumbUrl);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OSS_UPLOAD_FAILED", cause);
        }
    }
}
