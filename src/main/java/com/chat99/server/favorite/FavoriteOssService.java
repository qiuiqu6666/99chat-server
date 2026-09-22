package com.chat99.server.favorite;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.oss.OssProperties;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FavoriteOssService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov");

    private final OssClient oss;
    private final OssProperties props;
    private final FavoriteProperties favoriteProps;
    private final ImageProcessor processor;
    private final FavoriteMediaFetcher fetcher;

    public FavoriteOssService(OssClient oss, OssProperties props, FavoriteProperties favoriteProps,
                              ImageProcessor processor, FavoriteMediaFetcher fetcher) {
        this.oss = oss;
        this.props = props;
        this.favoriteProps = favoriteProps;
        this.processor = processor;
        this.fetcher = fetcher;
    }

    private long maxUploadBytes() {
        return favoriteProps.maxUploadBytes();
    }

    public record StoredMedia(String thumbUrl, String mediaUrl, String thumbObjectKey, String mediaObjectKey,
                              Integer width, Integer height) {}

    public void requireOss() {
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
    }

    public StoredMedia storeImageBytes(String userId, byte[] raw) throws IOException {
        requireOss();
        BufferedImage img = processor.decode(raw);
        return storeImageFromBuffered(userId, img);
    }

    public StoredMedia storeImageFromRemote(String userId, String remoteUrl, String remoteThumbUrl) throws IOException {
        byte[] main = fetcher.fetch(remoteUrl, maxUploadBytes());
        StoredMedia media = storeImageBytes(userId, main);
        if (remoteThumbUrl != null && !remoteThumbUrl.isBlank()) {
            try {
                byte[] thumbRaw = fetcher.fetch(remoteThumbUrl, props.maxUploadBytes());
                BufferedImage thumbImg = processor.decode(thumbRaw);
                String base = objectKeyBase(userId);
                int q = props.jpegQuality();
                byte[] thumbJpeg = processor.cropCenterSquare(thumbImg, props.thumbSize(), q);
                String thumbKey = base + "_thumb.jpg";
                String thumbUrl = oss.putBytes(thumbKey, thumbJpeg, "image/jpeg");
                return new StoredMedia(thumbUrl, media.mediaUrl(), thumbKey, media.mediaObjectKey(),
                    media.width(), media.height());
            } catch (ResponseStatusException ignored) {
                return media;
            }
        }
        return media;
    }

    public StoredMedia storeVideoBytes(String userId, byte[] videoBytes, byte[] thumbBytes) throws IOException {
        requireOss();
        String base = objectKeyBase(userId);
        String videoKey = base + "_video.mp4";
        String mediaUrl = oss.putBytes(videoKey, videoBytes, "video/mp4");
        String thumbKey = null;
        String thumbUrl = null;
        if (thumbBytes != null && thumbBytes.length > 0) {
            BufferedImage thumbImg = processor.decode(thumbBytes);
            byte[] thumbJpeg = processor.cropCenterSquare(thumbImg, props.thumbSize(), props.jpegQuality());
            thumbKey = base + "_thumb.jpg";
            thumbUrl = oss.putBytes(thumbKey, thumbJpeg, "image/jpeg");
        }
        return new StoredMedia(thumbUrl, mediaUrl, thumbKey, videoKey, null, null);
    }

    public StoredMedia storeVideoFromRemote(String userId, String remoteMediaUrl, String remoteThumbUrl)
        throws IOException {
        byte[] video = fetcher.fetch(remoteMediaUrl, maxUploadBytes());
        byte[] thumb = null;
        if (remoteThumbUrl != null && !remoteThumbUrl.isBlank()) {
            try {
                thumb = fetcher.fetch(remoteThumbUrl, maxUploadBytes());
            } catch (ResponseStatusException ignored) {
                thumb = null;
            }
        }
        return storeVideoBytes(userId, video, thumb);
    }

    public byte[] readImageFile(MultipartFile file) throws IOException {
        validateImageFile(file);
        return file.getBytes();
    }

    public byte[] readVideoFile(MultipartFile file) throws IOException {
        validateVideoFile(file);
        return file.getBytes();
    }

    public byte[] readOptionalImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }
        validateImageFile(file);
        return file.getBytes();
    }

    private StoredMedia storeImageFromBuffered(String userId, BufferedImage img) throws IOException {
        String base = objectKeyBase(userId);
        int q = props.jpegQuality();
        byte[] originJpeg = processor.toJpeg(img, q);
        byte[] previewJpeg = processor.resizeKeepAspect(img, props.previewLongEdge(), q);
        byte[] thumbJpeg = processor.cropCenterSquare(img, props.thumbSize(), q);
        String originKey = base + "_origin.jpg";
        String previewKey = base + "_preview.jpg";
        String thumbKey = base + "_thumb.jpg";
        String mediaUrl = oss.putBytes(originKey, originJpeg, "image/jpeg");
        oss.putBytes(previewKey, previewJpeg, "image/jpeg");
        String thumbUrl = oss.putBytes(thumbKey, thumbJpeg, "image/jpeg");
        return new StoredMedia(thumbUrl, mediaUrl, thumbKey, originKey, img.getWidth(), img.getHeight());
    }

    private String objectKeyBase(String userId) {
        return props.userFavoritePrefix() + userId + "/" + Instant.now().toEpochMilli()
            + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void validateImageFile(MultipartFile file) {
        validateFile(file);
        if (!isAllowedImage(file)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE");
        }
    }

    private void validateVideoFile(MultipartFile file) {
        validateFile(file);
        if (!isAllowedVideo(file)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        if (file.getSize() > maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
    }

    private boolean isAllowedImage(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && IMAGE_TYPES.contains(contentType.toLowerCase())) {
            return true;
        }
        return extensionAllowed(file, IMAGE_EXT);
    }

    private boolean isAllowedVideo(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && VIDEO_TYPES.contains(contentType.toLowerCase())) {
            return true;
        }
        return extensionAllowed(file, VIDEO_EXT);
    }

    private static boolean extensionAllowed(MultipartFile file, Set<String> exts) {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            return false;
        }
        String ct = file.getContentType();
        boolean ambiguous = ct == null || ct.equalsIgnoreCase("application/octet-stream");
        if (!ambiguous) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return exts.contains(filename.substring(dot + 1).toLowerCase());
    }
}
