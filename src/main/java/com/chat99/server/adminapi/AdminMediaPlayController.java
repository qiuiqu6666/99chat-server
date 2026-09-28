package com.chat99.server.adminapi;

import com.chat99.server.oss.OssClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 相册视频在 OSS 上带了 attachment / 强制下载，浏览器不能内嵌播放。
 * 先签一个 inline 地址；H.265 等浏览器解不了的，再转成 H.264 流。
 */
@RestController
@RequestMapping("/api/v1/media")
public class AdminMediaPlayController {

    private static final Logger log = LoggerFactory.getLogger(AdminMediaPlayController.class);
    private static final String ALBUM_HOST = "99chat.oss-cn-hongkong.aliyuncs.com";
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        ALBUM_HOST,
        "image.99chat.vip");
    private static final Semaphore TRANSCODES = new Semaphore(1);
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private final OssClient ossClient;

    public AdminMediaPlayController(OssClient ossClient) {
        this.ossClient = ossClient;
    }

    public record PlayUrlsRequest(List<String> urls) {}

    public record PlayUrlItem(String url, String playUrl) {}

    public record PlayUrlsResponse(List<PlayUrlItem> items) {}

    @PostMapping("/play-urls")
    public PlayUrlsResponse playUrls(Authentication auth, @RequestBody PlayUrlsRequest body) {
        AdminAccess.requirePermission(auth, "user.read");
        List<String> urls = body == null || body.urls() == null ? List.of() : body.urls();
        if (urls.size() > 80) {
            urls = urls.subList(0, 80);
        }
        List<PlayUrlItem> items = new ArrayList<>();
        for (String url : urls) {
            String play = signAlbumInline(url);
            items.add(new PlayUrlItem(url, play == null ? url : play));
        }
        return new PlayUrlsResponse(items);
    }

    @GetMapping("/file")
    public void file(
        Authentication auth,
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestParam("url") String url) throws IOException, InterruptedException {
        AdminAccess.requirePermission(auth, "user.read");
        String safe = requireAllowed(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(safe)).GET();
        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            builder.header("Range", range);
        }
        HttpResponse<InputStream> upstream = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = upstream.statusCode();
        if (status != 200 && status != 206) {
            upstream.body().close();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "UPSTREAM_" + status);
        }
        response.setStatus(status);
        String type = upstream.headers().firstValue("content-type").orElse(contentType(safe));
        if (type.startsWith("application/xml") || type.startsWith("text/")) {
            type = contentType(safe);
        }
        response.setContentType(type);
        response.setHeader("Content-Disposition", "inline");
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, max-age=300");
        upstream.headers().firstValue("content-length").ifPresent(value -> response.setHeader("Content-Length", value));
        upstream.headers().firstValue("content-range").ifPresent(value -> response.setHeader("Content-Range", value));
        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @GetMapping("/h264")
    public ResponseEntity<StreamingResponseBody> h264(
        Authentication auth,
        HttpServletResponse response,
        @RequestParam("url") String url) {
        AdminAccess.requirePermission(auth, "user.read");
        String safe = requireAllowed(url);
        if (!TRANSCODES.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "BUSY");
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Disposition", "inline");
        StreamingResponseBody body = output -> {
            Process process = null;
            try {
                process = new ProcessBuilder(
                    "ffmpeg",
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", safe,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "28",
                    "-vf", "scale=w='min(1280,iw)':h=-2",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                    "-f", "mp4",
                    "pipe:1")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                try (InputStream in = process.getInputStream()) {
                    in.transferTo(output);
                }
            } catch (IOException e) {
                log.warn("video transcode stream closed: {}", e.getMessage());
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
                TRANSCODES.release();
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).body(body);
    }

    private String signAlbumInline(String raw) {
        URI uri = parseHttps(raw);
        if (uri == null || uri.getHost() == null || !ALBUM_HOST.equalsIgnoreCase(uri.getHost())) {
            return null;
        }
        String key = objectKey(uri);
        if (key == null) {
            return null;
        }
        try {
            return ossClient.presignGet(key, 3600, contentType(key), "inline");
        } catch (RuntimeException e) {
            log.warn("album presign failed: {}", e.getMessage());
            return null;
        }
    }

    private static String requireAllowed(String raw) {
        URI uri = parseHttps(raw);
        if (uri == null || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_URL");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_HOSTS.contains(host) || host.endsWith(".myqcloud.com");
        if (!allowed || raw.contains("\r") || raw.contains("\n") || raw.contains(" ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_URL");
        }
        return raw.trim();
    }

    private static URI parseHttps(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String objectKey(URI uri) {
        String path = uri.getPath();
        if (path == null || path.length() < 2 || path.contains("..")) {
            return null;
        }
        return java.net.URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8);
    }

    private static String contentType(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        return "video/mp4";
    }
}
