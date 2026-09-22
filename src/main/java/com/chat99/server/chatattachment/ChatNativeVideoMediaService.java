package com.chat99.server.chatattachment;

import com.aliyun.oss.model.OSSObject;
import com.chat99.server.oss.OssPublicUrl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatNativeVideoMediaService {

    private static final Logger log = LoggerFactory.getLogger(ChatNativeVideoMediaService.class);

    private final ChatAttachmentProperties props;
    private final ChatAttachmentOssClient oss;
    private final ChatAttachmentRepository attachmentRepository;

    public ChatNativeVideoMediaService(ChatAttachmentProperties props,
                                       ChatAttachmentOssClient oss,
                                       ChatAttachmentRepository attachmentRepository) {
        this.props = props;
        this.oss = oss;
        this.attachmentRepository = attachmentRepository;
    }

    public String videoUrl(ChatAttachment video) {
        return publicUrl("video", video.getAttachmentId(), video.getExpiresAt(), null);
    }

    public String thumbUrl(ChatAttachment video) {
        return publicUrl("thumb", video.getAttachmentId(), video.getExpiresAt(), null);
    }

    public String videoUrl(ChatAttachment video, String mediaBaseUrl) {
        return objectCdnUrl(video.getObjectKey(), mediaBaseUrl);
    }

    public String thumbUrl(ChatAttachment thumb, String mediaBaseUrl) {
        return objectCdnUrl(thumb.getObjectKey(), mediaBaseUrl);
    }

    public String resolvePublicBaseUrl(HttpServletRequest request) {
        String configured = props.mediaPublicBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "https://image.99chat.vip";
    }

    static String objectCdnUrl(String objectKey, String mediaBaseUrl) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String base = mediaBaseUrl == null || mediaBaseUrl.isBlank()
            ? "https://image.99chat.vip"
            : mediaBaseUrl.trim().replaceAll("/+$", "");
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        return base + "/" + OssPublicUrl.encodeObjectKey(key);
    }

    static String originFrom(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String proto = firstHop(request.getHeader("X-Forwarded-Proto"));
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        proto = proto == null ? "" : proto.trim().toLowerCase(Locale.ROOT);
        if (!"http".equals(proto) && !"https".equals(proto)) {
            proto = "https";
        }
        String host = firstHop(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && !(("http".equals(proto) && port == 80) || ("https".equals(proto) && port == 443))) {
                host = host + ":" + port;
            }
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.indexOf('/') >= 0 || host.indexOf(' ') >= 0 || host.startsWith("[")) {
            return null;
        }
        if ("https".equals(proto) && host.endsWith(":443")) {
            host = host.substring(0, host.length() - 4);
        }
        if ("http".equals(proto) && host.endsWith(":80")) {
            host = host.substring(0, host.length() - 3);
        }
        return proto + "://" + host;
    }

    private static String firstHop(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        String hop = comma < 0 ? header.trim() : header.substring(0, comma).trim();
        return hop.isEmpty() ? null : hop;
    }

    public String publicUrl(String purpose, String attachmentId, Instant expiresAt) {
        return publicUrl(purpose, attachmentId, expiresAt, null);
    }

    public String publicUrl(String purpose, String attachmentId, Instant expiresAt, String mediaBaseUrl) {
        if (props.mediaHmacSecret() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        String base = mediaBaseUrl == null || mediaBaseUrl.isBlank()
            ? props.mediaPublicBaseUrl()
            : mediaBaseUrl.trim().replaceAll("/+$", "");
        if (base == null || base.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        Instant exp = expiresAt == null
            ? Instant.now().plusSeconds(props.confirmedRetentionDays() * 86400L)
            : expiresAt;
        String token = ChatNativeVideoMediaToken.mint(purpose, attachmentId, exp, props.mediaHmacSecret());
        return base + "/chat-media/v1/" + token;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, String token) {
        ChatNativeVideoMediaToken.Claims claims =
            ChatNativeVideoMediaToken.verify(token, props.mediaHmacSecret());
        if (claims == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        Instant now = Instant.now();
        if (claims.expiresAt() != null && claims.expiresAt().isBefore(now)) {
            response.setStatus(HttpStatus.GONE.value());
            return;
        }
        ChatAttachment video = attachmentRepository.findByAttachmentId(claims.attachmentId()).orElse(null);
        if (video == null || video.getKind() != ChatAttachmentKind.video
            || video.getStatus() != ChatAttachmentStatus.ready) {
            response.setStatus(HttpStatus.GONE.value());
            return;
        }
        if (video.getExpiresAt() != null && video.getExpiresAt().isBefore(now)) {
            response.setStatus(HttpStatus.GONE.value());
            return;
        }
        ChatAttachment target = video;
        if ("thumb".equals(claims.purpose())) {
            if (video.getThumbnailAttachmentId() == null) {
                response.setStatus(HttpStatus.GONE.value());
                return;
            }
            target = attachmentRepository.findByAttachmentId(video.getThumbnailAttachmentId()).orElse(null);
            if (target == null || target.getStatus() != ChatAttachmentStatus.ready) {
                response.setStatus(HttpStatus.GONE.value());
                return;
            }
        }
        ChatAttachmentOssClient.HeadResult head;
        try {
            head = oss.head(target.getObjectKey());
        } catch (Exception e) {
            log.warn("chat native media head failed attachmentId={}", video.getAttachmentId());
            response.setStatus(HttpStatus.GONE.value());
            return;
        }
        long total = head.sizeBytes();
        Long start = null;
        Long end = null;
        int status = 200;
        String rangeHeader = request.getHeader("Range");
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            long[] span = parseRange(rangeHeader, total);
            if (span == null) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader("Content-Range", "bytes */" + total);
                return;
            }
            start = span[0];
            end = span[1];
            status = 206;
        }
        String contentType = "thumb".equals(claims.purpose()) ? "image/jpeg" : "video/mp4";
        long contentLength = start == null ? total : (end - start + 1);
        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Type", contentType);
        response.setContentLengthLong(contentLength);
        response.setHeader("Content-Length", String.valueOf(contentLength));
        response.setHeader("Cache-Control", "private, no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        if (status == 206) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        }
        log.info("chat native media attachmentId={} purpose={} status={} range={} length={}",
            video.getAttachmentId(), claims.purpose(), status,
            rangeHeader == null || rangeHeader.isBlank() ? "-" : rangeHeader.trim(),
            contentLength);
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        try (OSSObject obj = oss.openObject(target.getObjectKey(), start, end);
             InputStream in = obj.getObjectContent();
             OutputStream out = response.getOutputStream()) {
            copyExactly(in, out, contentLength);
            out.flush();
        } catch (Exception e) {
            log.warn("chat native media stream failed attachmentId={} err={}",
                video.getAttachmentId(), e.getMessage());
            if (!response.isCommitted()) {
                response.setStatus(HttpStatus.GONE.value());
            }
        }
    }

    static void copyExactly(InputStream in, OutputStream out, long contentLength) throws java.io.IOException {
        byte[] buf = new byte[8192];
        long remaining = contentLength;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
        if (remaining != 0) {
            throw new java.io.IOException("short media body remaining=" + remaining);
        }
    }

    static long[] parseRange(String header, long total) {
        String raw = header.trim();
        if (!raw.startsWith("bytes=")) {
            return null;
        }
        raw = raw.substring(6).trim();
        int comma = raw.indexOf(',');
        if (comma >= 0) {
            raw = raw.substring(0, comma).trim();
        }
        int dash = raw.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String left = raw.substring(0, dash).trim();
        String right = raw.substring(dash + 1).trim();
        try {
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0 || total <= 0) {
                    return null;
                }
                long start = Math.max(0, total - suffix);
                return new long[] {start, total - 1};
            }
            long start = Long.parseLong(left);
            long end = right.isEmpty() ? total - 1 : Long.parseLong(right);
            if (start < 0 || start >= total || end < start) {
                return null;
            }
            if (end >= total) {
                end = total - 1;
            }
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
