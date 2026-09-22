package com.chat99.server.kefu;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 客服统一入口：外部只访问主服务，主服务把 {@code /kefu/public/**}、{@code /kefu/rails/**}
 * 转发到本机 Chatwoot（默认 http://127.0.0.1:3000）。
 * <p>
 * 例：POST /kefu/public/api/v1/inboxes/{inbox}/contacts
 * → http://127.0.0.1:3000/public/api/v1/inboxes/{inbox}/contacts。
 * WebSocket {@code /kefu/cable} 由 {@link KefuCableProxyHandler} 处理，不走本控制器。
 */
@ConditionalOnProperty(name = "kefu.proxy-enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class KefuServiceProxyController {

    private static final Logger log = LoggerFactory.getLogger(KefuServiceProxyController.class);

    static final String PREFIX = "/kefu";

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
        "host", "connection", "content-length", "transfer-encoding", "upgrade",
        "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer", "expect",
        "origin", "referer", "cookie");

    private static final Set<String> PASS_RESPONSE_HEADERS = Set.of(
        "content-type", "content-disposition", "cache-control", "x-accel-buffering",
        "etag", "last-modified", "location", "content-range", "accept-ranges");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final String kefuServiceUrl;
    private final String upstreamPublicUrl;
    private final String configuredPublicBase;
    private final List<String> rewriteOrigins;

    public KefuServiceProxyController(
            @Value("${kefu.service-url:http://127.0.0.1:3000}") String kefuServiceUrl,
            @Value("${kefu.upstream-public-url:http://43.154.162.29}") String upstreamPublicUrl,
            @Value("${kefu.public-base-url:}") String configuredPublicBase) {
        this.kefuServiceUrl = KefuUrlRewriter.stripSlash(kefuServiceUrl);
        this.upstreamPublicUrl = KefuUrlRewriter.stripSlash(upstreamPublicUrl);
        this.configuredPublicBase = KefuUrlRewriter.stripSlash(configuredPublicBase);
        this.rewriteOrigins = KefuUrlRewriter.upstreamOrigins(this.kefuServiceUrl, this.upstreamPublicUrl);
    }

    @RequestMapping(
        value = {PREFIX + "/public/**", PREFIX + "/rails/**"},
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                  RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD})
    public ResponseEntity<StreamingResponseBody> proxy(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI().substring(PREFIX.length());
        if (path.isEmpty()) {
            path = "/";
        }
        String query = request.getQueryString();
        URI target = URI.create(kefuServiceUrl + path + (query != null ? "?" + query : ""));
        String publicBase = KefuUrlRewriter.publicBaseFrom(request, configuredPublicBase);

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(180));

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] body = KefuRequestBodies.read(request);
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    builder.header(name, values.nextElement());
                } catch (IllegalArgumentException ignored) {
                    // JDK HttpClient 拒绝的受限头，跳过
                }
            }
        }
        if (!upstreamPublicUrl.isEmpty()) {
            try {
                builder.setHeader("Origin", upstreamPublicUrl);
            } catch (IllegalArgumentException ignored) {
                // ignore
            }
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("kefu proxy failed path={} target={} reason={}", path, target, e.toString());
            byte[] msg = "{\"ok\":false,\"error\":\"kefu service unavailable\"}"
                .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body(os -> os.write(msg));
        }

        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (PASS_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                if ("location".equalsIgnoreCase(name)) {
                    values.forEach(v -> headers.add(name, rewriteLocation(v, publicBase)));
                } else {
                    values.forEach(v -> headers.add(name, v));
                }
            }
        });

        Optional<String> contentType = response.headers().firstValue("content-type");
        InputStream upstream = response.body();
        if (shouldRewrite(contentType.orElse(""))) {
            byte[] raw;
            try (InputStream in = upstream) {
                raw = in.readAllBytes();
            }
            Charset charset = charsetOf(contentType.orElse(""));
            String rewritten = KefuUrlRewriter.rewrite(new String(raw, charset), publicBase, rewriteOrigins);
            byte[] out = rewritten.getBytes(charset);
            return ResponseEntity.status(response.statusCode()).headers(headers).body(os -> os.write(out));
        }

        StreamingResponseBody body = (OutputStream os) -> {
            try (InputStream in = upstream) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    os.flush();
                }
            } catch (IOException e) {
                // 客户端断开或上游关闭，正常结束
            }
        };
        return ResponseEntity.status(response.statusCode()).headers(headers).body(body);
    }

    private String rewriteLocation(String location, String publicBase) {
        if (location == null || location.isEmpty()) {
            return location;
        }
        if (location.startsWith("/")) {
            return publicBase + location;
        }
        return KefuUrlRewriter.rewrite(location, publicBase, rewriteOrigins);
    }

    private static boolean shouldRewrite(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("json") || ct.contains("javascript") || ct.startsWith("text/");
    }

    private static Charset charsetOf(String contentType) {
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i >= 0) {
            String raw = contentType.substring(i + 8).trim();
            int semi = raw.indexOf(';');
            if (semi >= 0) {
                raw = raw.substring(0, semi).trim();
            }
            raw = raw.replace("\"", "");
            try {
                return Charset.forName(raw);
            } catch (Exception ignored) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
