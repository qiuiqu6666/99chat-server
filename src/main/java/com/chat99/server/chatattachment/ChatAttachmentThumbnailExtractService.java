package com.chat99.server.chatattachment;

import com.chat99.server.oss.ImageProcessor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatAttachmentThumbnailExtractService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentThumbnailExtractService.class);
    private static final int EXTRACT_TIMEOUT_SECONDS = 60;
    /** 终态失败（对象缺失 / ffmpeg 失败 / 解码失败 / 超限）后的负缓存时长，避免回填任务每 2 分钟重复起 ffmpeg。 */
    private static final long SKIP_TTL_MS = 6L * 60 * 60 * 1000;

    private final ChatAttachmentProperties props;
    private final ChatAttachmentOssClient oss;
    private final ChatAttachmentRepository attachmentRepository;
    private final ImageProcessor imageProcessor;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> skipUntil = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "chat-att-thumb");
        t.setDaemon(true);
        return t;
    });

    public ChatAttachmentThumbnailExtractService(ChatAttachmentProperties props,
                                                 ChatAttachmentOssClient oss,
                                                 ChatAttachmentRepository attachmentRepository,
                                                 ImageProcessor imageProcessor) {
        this.props = props;
        this.oss = oss;
        this.attachmentRepository = attachmentRepository;
        this.imageProcessor = imageProcessor;
    }

    public void submitEnsure(String videoAttachmentId) {
        if (videoAttachmentId == null || videoAttachmentId.isBlank()) {
            return;
        }
        worker.execute(() -> {
            try {
                ensureThumbnail(videoAttachmentId);
            } catch (Exception e) {
                log.warn("chat-att thumb extract async failed attachmentId={} err={}",
                    videoAttachmentId, e.getMessage());
            }
        });
    }

    public ChatAttachment ensureThumbnail(String videoAttachmentId) {
        Object lock = locks.computeIfAbsent(videoAttachmentId, key -> new Object());
        try {
            synchronized (lock) {
                return ensureLocked(videoAttachmentId);
            }
        } finally {
            locks.remove(videoAttachmentId, lock);
        }
    }

    public int backfillReadyVideos(int limit) {
        List<ChatAttachment> missing = attachmentRepository
            .findByKindAndStatusAndThumbnailAttachmentIdIsNullAndParentAttachmentIdIsNull(
                ChatAttachmentKind.video, ChatAttachmentStatus.ready);
        int done = 0;
        for (ChatAttachment video : missing) {
            if (done >= limit) {
                break;
            }
            if (isSkipped(video.getAttachmentId())) {
                continue;
            }
            ChatAttachment thumb = ensureThumbnail(video.getAttachmentId());
            if (thumb != null) {
                done++;
            }
        }
        return done;
    }

    private void markSkip(String attachmentId) {
        if (attachmentId != null) {
            skipUntil.put(attachmentId, System.currentTimeMillis() + SKIP_TTL_MS);
        }
    }

    private boolean isSkipped(String attachmentId) {
        if (attachmentId == null) {
            return false;
        }
        Long until = skipUntil.get(attachmentId);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            skipUntil.remove(attachmentId, until);
            return false;
        }
        return true;
    }

    private ChatAttachment ensureLocked(String videoAttachmentId) {
        ChatAttachment video = attachmentRepository.findByAttachmentId(videoAttachmentId).orElse(null);
        if (video == null || video.getKind() != ChatAttachmentKind.video
            || video.getStatus() != ChatAttachmentStatus.ready) {
            return null;
        }
        ChatAttachment existing = resolveExisting(video);
        if (existing != null) {
            if (!existing.getAttachmentId().equals(video.getThumbnailAttachmentId())) {
                video.setThumbnailAttachmentId(existing.getAttachmentId());
                attachmentRepository.save(video);
            }
            return existing;
        }
        if (!oss.exists(video.getObjectKey())) {
            log.warn("chat-att thumb extract skipped missing object attachmentId={}", video.getAttachmentId());
            markSkip(video.getAttachmentId());
            return null;
        }
        byte[] jpeg;
        try {
            jpeg = extractJpeg(video);
        } catch (Exception e) {
            log.warn("chat-att thumb extract failed attachmentId={} err={}", video.getAttachmentId(), e.getMessage());
            markSkip(video.getAttachmentId());
            return null;
        }
        if (jpeg == null || jpeg.length == 0 || jpeg.length > props.thumbnailMaxBytes()
            || !ChatAttachmentContentDisposition.isJpeg(jpeg)) {
            log.warn("chat-att thumb extract invalid jpeg attachmentId={} bytes={}",
                video.getAttachmentId(), jpeg == null ? 0 : jpeg.length);
            markSkip(video.getAttachmentId());
            return null;
        }
        BufferedImage image;
        try {
            image = imageProcessor.decode(jpeg);
        } catch (Exception e) {
            log.warn("chat-att thumb decode failed attachmentId={} err={}", video.getAttachmentId(), e.getMessage());
            markSkip(video.getAttachmentId());
            return null;
        }
        int longEdge = Math.max(image.getWidth(), image.getHeight());
        if (longEdge > props.thumbnailMaxLongEdge()) {
            // 竖屏视频 ffmpeg 只限宽不限高，长边可能超限：等比缩放而不是放弃
            try {
                jpeg = imageProcessor.resizeKeepAspect(image, props.thumbnailMaxLongEdge(), 85);
                image = imageProcessor.decode(jpeg);
            } catch (Exception e) {
                log.warn("chat-att thumb downscale failed attachmentId={} err={}",
                    video.getAttachmentId(), e.getMessage());
                markSkip(video.getAttachmentId());
                return null;
            }
            log.info("chat-att thumb downscaled attachmentId={} from={} to={}",
                video.getAttachmentId(), longEdge, Math.max(image.getWidth(), image.getHeight()));
            if (jpeg.length > props.thumbnailMaxBytes()) {
                log.warn("chat-att thumb too large after downscale attachmentId={} bytes={}",
                    video.getAttachmentId(), jpeg.length);
                markSkip(video.getAttachmentId());
                return null;
            }
        }
        Instant now = Instant.now();
        String thumbId = ChatAttachmentIds.attachment();
        String objectKey = ChatAttachmentContentDisposition.objectKey(
            "chat-attachments/v1/thumbnails", video.getOwnerUserId(), thumbId);
        oss.putBytes(objectKey, jpeg, "image/jpeg");
        ChatAttachment thumb = new ChatAttachment();
        thumb.setAttachmentId(thumbId);
        thumb.setOwnerUserId(video.getOwnerUserId());
        thumb.setParentAttachmentId(video.getAttachmentId());
        thumb.setStorageProvider("aliyun-oss");
        thumb.setBucket(oss.bucket());
        thumb.setObjectKey(objectKey);
        thumb.setOriginalName("thumbnail.jpg");
        thumb.setKind(ChatAttachmentKind.image);
        thumb.setNativeMessageKind(ChatNativeMessageKind.image);
        thumb.setMimeType("image/jpeg");
        thumb.setDeclaredSizeBytes(jpeg.length);
        thumb.setSizeBytes((long) jpeg.length);
        thumb.setChecksumStatus(ChatChecksumStatus.unverified);
        thumb.setStatus(ChatAttachmentStatus.ready);
        thumb.setWidth(image.getWidth());
        thumb.setHeight(image.getHeight());
        thumb.setCreatedAt(now);
        thumb.setReadyAt(now);
        thumb.setExpiresAt(video.getExpiresAt() != null
            ? video.getExpiresAt()
            : now.plusSeconds(props.confirmedRetentionDays() * 86400L));
        attachmentRepository.save(thumb);
        video.setThumbnailAttachmentId(thumbId);
        attachmentRepository.save(video);
        log.info("chat-att thumb extract bound video={} thumbnail={} bytes={}",
            video.getAttachmentId(), thumbId, jpeg.length);
        return thumb;
    }

    private ChatAttachment resolveExisting(ChatAttachment video) {
        if (video.getThumbnailAttachmentId() != null) {
            ChatAttachment bound = attachmentRepository.findByAttachmentId(video.getThumbnailAttachmentId())
                .orElse(null);
            if (bound != null && bound.getStatus() == ChatAttachmentStatus.ready) {
                return bound;
            }
        }
        List<ChatAttachment> children = attachmentRepository.findByParentAttachmentId(video.getAttachmentId());
        for (ChatAttachment child : children) {
            if (child.getKind() == ChatAttachmentKind.image
                && child.getStatus() == ChatAttachmentStatus.ready) {
                return child;
            }
        }
        return null;
    }

    private byte[] extractJpeg(ChatAttachment video) throws IOException, InterruptedException {
        String ffmpeg = ffmpegBinary();
        if (ffmpeg == null) {
            log.warn("chat-att thumb extract skipped ffmpeg missing");
            return null;
        }
        String source = oss.presignGet(video.getObjectKey(), 300, null, null);
        Path out = Files.createTempFile("chat-att-thumb-", ".jpg");
        try {
            if (runFfmpeg(ffmpeg, source, out, "1") == 0 && Files.size(out) > 0) {
                return Files.readAllBytes(out);
            }
            if (runFfmpeg(ffmpeg, source, out, "0") == 0 && Files.size(out) > 0) {
                return Files.readAllBytes(out);
            }
            return null;
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private int runFfmpeg(String ffmpeg, String source, Path out, String startSeconds)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            ffmpeg,
            "-hide_banner",
            "-nostdin",
            "-y",
            "-ss", startSeconds,
            "-i", source,
            "-frames:v", "1",
            "-vf", "scale='min(" + props.thumbnailMaxLongEdge() + ",iw)':-2",
            "-q:v", "3",
            out.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.warn("chat-att thumb ffmpeg timeout start={}", startSeconds);
            return -1;
        }
        return process.exitValue();
    }

    private static String ffmpegBinary() {
        Path bundled = Path.of("/usr/bin/ffmpeg");
        if (Files.isExecutable(bundled)) {
            return bundled.toString();
        }
        return null;
    }
}
