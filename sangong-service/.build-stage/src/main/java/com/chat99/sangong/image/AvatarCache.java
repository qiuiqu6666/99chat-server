package com.chat99.sangong.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 头像加速（与 PHP AvatarCacheService 行为对齐）：内存缓存 + 限流并行预取。
 * 默认头像（/moren/、default_c2c_head）不拉图，报表直接画灰占位。
 */
@Service
public class AvatarCache {
    private static final Logger log = LoggerFactory.getLogger(AvatarCache.class);
    private static final long TTL_SECONDS = 86400;
    private static final int CONCURRENCY = 8;

    private record Cached(byte[] data, long cachedAtEpoch) {}

    private final OkHttpClient http;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public AvatarCache() {
        this.http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
    }

    /** IM/OSS 默认头像不拉图。 */
    public boolean isDefaultFaceUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String path = url;
        try {
            String parsed = URI.create(url.trim()).getPath();
            if (parsed != null && !parsed.isBlank()) {
                path = parsed;
            }
        } catch (Exception ignored) {
            // 非法 URL 按原文匹配
        }
        String lower = path.toLowerCase();
        return lower.contains("/moren/") || lower.contains("default_c2c_head");
    }

    /** 并行预取头像字节到内存（已缓存的跳过）。 */
    public void prefetch(List<String> urls) {
        Set<String> unique = new LinkedHashSet<>();
        for (String url : urls) {
            if (url == null) continue;
            String u = url.trim();
            if (u.isEmpty() || isDefaultFaceUrl(u)) continue;
            if (freshCached(u) == null) {
                unique.add(u);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        java.util.concurrent.Semaphore limiter = new java.util.concurrent.Semaphore(CONCURRENCY);
        for (String url : unique) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    limiter.acquire();
                    fetchAndCache(url);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    limiter.release();
                }
            }));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("avatar prefetch incomplete: {}", e.getMessage());
        }
    }

    /**
     * 加载并缩放为正方形图；加载失败返回灰占位方块（与 PHP loadSquare 一致）。
     */
    public BufferedImage loadSquare(String url, int size, Color placeholderColor) {
        size = Math.max(8, Math.min(512, size));
        BufferedImage src = null;
        if (url != null && !url.isBlank() && !isDefaultFaceUrl(url.trim())) {
            byte[] data = readRaw(url.trim());
            if (data != null && data.length > 0) {
                try {
                    src = ImageIO.read(new ByteArrayInputStream(data));
                } catch (IOException ignored) {
                    // 解码失败画占位
                }
            }
        }
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            if (src != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, size, size, null);
            } else {
                g.setColor(placeholderColor);
                g.fillRect(0, 0, size, size);
            }
        } finally {
            g.dispose();
        }
        return dst;
    }

    private byte[] readRaw(String url) {
        byte[] cached = freshCached(url);
        if (cached != null) {
            return cached;
        }
        return fetchAndCache(url);
    }

    private byte[] freshCached(String url) {
        Cached cached = cache.get(url);
        if (cached != null && Instant.now().getEpochSecond() - cached.cachedAtEpoch() <= TTL_SECONDS) {
            return cached.data();
        }
        return null;
    }

    private byte[] fetchAndCache(String url) {
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "sangong-avatar-cache/1.0")
            .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                byte[] data = response.body().bytes();
                if (data.length > 0) {
                    cache.put(url, new Cached(data, Instant.now().getEpochSecond()));
                    return data;
                }
            }
        } catch (Exception e) {
            log.debug("avatar fetch failed url={} err={}", url, e.getMessage());
        }
        return null;
    }
}
