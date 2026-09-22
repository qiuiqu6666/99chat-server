package com.chat99.server.im;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.oss.OssProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImAnnouncementMediaResolver {

    private static final Logger log = LoggerFactory.getLogger(ImAnnouncementMediaResolver.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final ImageProcessor imageProcessor;
    private final OssClient ossClient;
    private final OssProperties ossProps;

    public ImAnnouncementMediaResolver(ImageProcessor imageProcessor,
                                       OssClient ossClient,
                                       OssProperties ossProps) {
        this.imageProcessor = imageProcessor;
        this.ossClient = ossClient;
        this.ossProps = ossProps;
    }

    public ImC2cImageContent resolveImage(Map<String, Object> payload, String fallbackUrl) {
        String originUrl = firstNonBlank(str(payload.get("imageUrl")), fallbackUrl);
        if (originUrl == null) {
            throw new IllegalArgumentException("image url required");
        }
        String previewUrl = firstNonBlank(str(payload.get("previewUrl")), str(payload.get("largeUrl")));
        String thumbUrl = str(payload.get("thumbUrl"));
        int width = intVal(payload.get("width"));
        int height = intVal(payload.get("height"));
        long originSize = longVal(payload.get("imageSize"));
        long largeSize = longVal(payload.get("previewSize"));
        long thumbSize = longVal(payload.get("thumbSize"));
        int thumbWidth = intVal(payload.get("thumbWidth"));
        int thumbHeight = intVal(payload.get("thumbHeight"));

        if (previewUrl == null || thumbUrl == null || width <= 0 || height <= 0 || originSize <= 0) {
            ResolvedImage resolved = hydrateFromUrl(originUrl);
            if (previewUrl == null) {
                previewUrl = resolved.previewUrl();
            }
            if (thumbUrl == null) {
                thumbUrl = resolved.thumbUrl();
            }
            if (width <= 0) {
                width = resolved.width();
            }
            if (height <= 0) {
                height = resolved.height();
            }
            if (originSize <= 0) {
                originSize = resolved.originSize();
            }
            if (largeSize <= 0) {
                largeSize = resolved.previewSize();
            }
            if (thumbSize <= 0) {
                thumbSize = resolved.thumbSize();
            }
            if (thumbWidth <= 0) {
                thumbWidth = resolved.thumbWidth();
            }
            if (thumbHeight <= 0) {
                thumbHeight = resolved.thumbHeight();
            }
        }

        if (previewUrl == null) {
            previewUrl = originUrl;
        }
        if (thumbUrl == null) {
            thumbUrl = previewUrl;
        }
        if (largeSize <= 0) {
            largeSize = originSize;
        }
        if (thumbSize <= 0) {
            thumbSize = Math.max(1L, largeSize / 10);
        }
        if (thumbWidth <= 0) {
            thumbWidth = Math.min(width, ossProps.thumbSize());
        }
        if (thumbHeight <= 0) {
            thumbHeight = Math.min(height, ossProps.thumbSize());
        }
        if (width <= 0) {
            width = 1;
        }
        if (height <= 0) {
            height = 1;
        }
        if (originSize <= 0) {
            originSize = 1;
        }

        return new ImC2cImageContent(
            imageUuid(originUrl),
            detectImageFormat(originUrl),
            originUrl,
            originSize,
            width,
            height,
            previewUrl,
            largeSize,
            thumbUrl,
            thumbSize,
            thumbWidth,
            thumbHeight);
    }

    public ImC2cVideoContent resolveVideo(Map<String, Object> payload, String fallbackUrl) {
        String videoUrl = firstNonBlank(str(payload.get("videoUrl")), fallbackUrl);
        if (videoUrl == null) {
            throw new IllegalArgumentException("video url required");
        }
        String thumbUrl = str(payload.get("thumbUrl"));
        long videoSize = longVal(payload.get("videoSize"));
        int videoSecond = intVal(payload.get("videoSecond"));
        long thumbSize = longVal(payload.get("thumbSize"));
        int thumbWidth = intVal(payload.get("thumbWidth"));
        int thumbHeight = intVal(payload.get("thumbHeight"));

        if (videoSize <= 0) {
            videoSize = probeContentLength(videoUrl);
        }
        if (videoSecond <= 0) {
            videoSecond = 1;
        }
        if (thumbUrl == null) {
            thumbUrl = uploadDefaultVideoThumb();
        }
        if (thumbSize <= 0 || thumbWidth <= 0 || thumbHeight <= 0) {
            ResolvedImage thumb = hydrateFromUrl(thumbUrl);
            if (thumbSize <= 0) {
                thumbSize = thumb.thumbSize();
            }
            if (thumbWidth <= 0) {
                thumbWidth = thumb.thumbWidth();
            }
            if (thumbHeight <= 0) {
                thumbHeight = thumb.thumbHeight();
            }
        }
        if (videoSize <= 0) {
            videoSize = 1;
        }

        return new ImC2cVideoContent(
            videoUrl,
            videoSize,
            videoSecond,
            detectVideoFormat(videoUrl),
            thumbUrl,
            thumbSize,
            thumbWidth,
            thumbHeight);
    }

    public ImageUploadBundle uploadImageVariants(byte[] raw) throws IOException {
        BufferedImage img = imageProcessor.decode(raw);
        int width = img.getWidth();
        int height = img.getHeight();
        int q = ossProps.jpegQuality();
        byte[] originJpeg = imageProcessor.toJpeg(img, q);
        byte[] previewJpeg = imageProcessor.resizeKeepAspect(img, ossProps.previewLongEdge(), q);
        byte[] thumbJpeg = imageProcessor.cropCenterSquare(img, ossProps.thumbSize(), q);
        String base = "admin/announcements/" + UUID.randomUUID();
        String originUrl = ossClient.putBytes(base + "_origin.jpg", originJpeg, "image/jpeg");
        String previewUrl = ossClient.putBytes(base + "_preview.jpg", previewJpeg, "image/jpeg");
        String thumbUrl = ossClient.putBytes(base + "_thumb.jpg", thumbJpeg, "image/jpeg");
        return new ImageUploadBundle(
            originUrl,
            previewUrl,
            thumbUrl,
            width,
            height,
            originJpeg.length,
            previewJpeg.length,
            thumbJpeg.length,
            ossProps.thumbSize(),
            ossProps.thumbSize(),
            base + "_origin.jpg");
    }

    public VideoUploadBundle uploadVideoWithThumb(byte[] raw, String ext, String contentType) throws IOException {
        String base = "admin/announcements/videos/" + UUID.randomUUID();
        String videoUrl = ossClient.putBytes(base + "." + ext, raw, contentType);
        String thumbUrl = uploadDefaultVideoThumb();
        return new VideoUploadBundle(videoUrl, thumbUrl, raw.length, base + "." + ext);
    }

    private String uploadDefaultVideoThumb() {
        try {
            int size = ossProps.thumbSize();
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(32, 32, 32));
            g.fillRect(0, 0, size, size);
            g.setColor(new Color(220, 220, 220));
            g.drawRect(8, 8, size - 16, size - 16);
            g.fillPolygon(
                new int[] {size / 3, size / 3, size * 2 / 3},
                new int[] {size / 4, size * 3 / 4, size / 2},
                3);
            g.dispose();
            byte[] thumbJpeg = imageProcessor.toJpeg(img, ossProps.jpegQuality());
            return ossClient.putBytes(
                "admin/announcements/video-thumb/" + UUID.randomUUID() + ".jpg",
                thumbJpeg,
                "image/jpeg");
        } catch (Exception e) {
            log.warn("default video thumb upload failed: {}", e.getMessage());
            return "https://99chat.oss-cn-hongkong.aliyuncs.com/moren/default_c2c_head.png";
        }
    }

    private ResolvedImage hydrateFromUrl(String url) {
        try {
            byte[] raw = downloadBytes(url);
            BufferedImage img = imageProcessor.decode(raw);
            int width = img.getWidth();
            int height = img.getHeight();
            int q = ossProps.jpegQuality();
            byte[] previewJpeg = imageProcessor.resizeKeepAspect(img, ossProps.previewLongEdge(), q);
            byte[] thumbJpeg = imageProcessor.cropCenterSquare(img, ossProps.thumbSize(), q);
            String base = "admin/announcements/cache/" + UUID.randomUUID();
            String previewUrl = ossClient.putBytes(base + "_preview.jpg", previewJpeg, "image/jpeg");
            String thumbUrl = ossClient.putBytes(base + "_thumb.jpg", thumbJpeg, "image/jpeg");
            return new ResolvedImage(
                url,
                previewUrl,
                thumbUrl,
                width,
                height,
                raw.length,
                previewJpeg.length,
                thumbJpeg.length,
                ossProps.thumbSize(),
                ossProps.thumbSize());
        } catch (Exception e) {
            log.warn("hydrate image from url failed url={} err={}", url, e.getMessage());
            long size = Math.max(1L, probeContentLength(url));
            return new ResolvedImage(url, url, url, 720, 720, size, size, Math.max(1L, size / 10), 200, 200);
        }
    }

    private long probeContentLength(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.headers().firstValueAsLong("content-length").orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private byte[] downloadBytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            throw new IOException("download failed status=" + resp.statusCode());
        }
        return resp.body();
    }

    private static String imageUuid(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        }
    }

    static int detectImageFormat(String url) {
        if (url == null) {
            return 1;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) {
            return 3;
        }
        if (lower.contains(".gif")) {
            return 2;
        }
        if (lower.contains(".bmp")) {
            return 4;
        }
        return 1;
    }

    static String detectVideoFormat(String url) {
        if (url == null) {
            return "mp4";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".webm")) {
            return "webm";
        }
        if (lower.contains(".mov")) {
            return "mov";
        }
        return "mp4";
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static int intVal(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public record ImageUploadBundle(
        String originUrl,
        String previewUrl,
        String thumbUrl,
        int width,
        int height,
        long originSize,
        long previewSize,
        long thumbSize,
        int thumbWidth,
        int thumbHeight,
        String objectKey) {}

    public record VideoUploadBundle(
        String videoUrl,
        String thumbUrl,
        long videoSize,
        String objectKey) {}

    private record ResolvedImage(
        String originUrl,
        String previewUrl,
        String thumbUrl,
        int width,
        int height,
        long originSize,
        long previewSize,
        long thumbSize,
        int thumbWidth,
        int thumbHeight) {}
}
