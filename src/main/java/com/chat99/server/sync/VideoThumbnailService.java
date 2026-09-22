package com.chat99.server.sync;

import com.chat99.server.oss.OssClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VideoThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(VideoThumbnailService.class);
    private static final String SVG = """
        <svg xmlns="http://www.w3.org/2000/svg" width="320" height="320" viewBox="0 0 320 320">
          <rect width="320" height="320" rx="24" fill="#111827"/>
          <circle cx="160" cy="160" r="72" fill="#374151"/>
          <path d="M140 116 L140 204 L212 160 Z" fill="#f9fafb"/>
        </svg>
        """;

    private final OssClient oss;
    private final SyncProperties syncProperties;
    private final UserPhotoRepository photoRepository;
    private volatile Thumbnail cached;
    private ScheduledExecutorService executor;

    public VideoThumbnailService(OssClient oss,
                                 SyncProperties syncProperties,
                                 UserPhotoRepository photoRepository) {
        this.oss = oss;
        this.syncProperties = syncProperties;
        this.photoRepository = photoRepository;
    }

    public synchronized Thumbnail defaultThumbnail() {
        if (cached != null) {
            return cached;
        }
        if (!oss.isConfigured()) {
            return null;
        }
        String key = syncProperties.photoPrefix() + "_system/video-thumb.svg";
        String url = oss.exists(key)
            ? oss.objectUrl(key)
            : oss.putBytes(key, SVG.getBytes(StandardCharsets.UTF_8), "image/svg+xml");
        cached = new Thumbnail(key, url);
        return cached;
    }

    @PostConstruct
    void startBackfill() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "video-thumbnail-backfill");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
            this::backfillMissingThumbnails, 20, 86_400, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopBackfill() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public void backfillMissingThumbnails() {
        try {
            Thumbnail thumbnail = defaultThumbnail();
            if (thumbnail == null) {
                return;
            }
            int updated = photoRepository.backfillMissingVideoThumb(
                thumbnail.objectKey(), thumbnail.url());
            if (updated > 0) {
                log.info("backfilled {} missing video thumbnails", updated);
            }
        } catch (Exception e) {
            log.warn("video thumbnail backfill failed: {}", e.getMessage());
        }
    }

    public record Thumbnail(String objectKey, String url) {}
}
