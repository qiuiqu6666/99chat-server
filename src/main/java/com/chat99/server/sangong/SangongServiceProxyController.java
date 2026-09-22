package com.chat99.server.sangong;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
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
 * 三公服务统一入口：外部只访问主服务，主服务把 /sangong/** 原样转发到内网 sangong-service。
 * 例：GET /sangong/api/v1/admin/session → http://127.0.0.1:8088/api/v1/admin/session。
 * 响应按流式回写，天然支持 SSE（/sangong/api/v1/admin/events/stream）与报表图片。
 */
@ConditionalOnProperty(name = "sangong.proxy-enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class SangongServiceProxyController {

    private static final Logger log = LoggerFactory.getLogger(SangongServiceProxyController.class);

    private static final String PREFIX = "/sangong";

    /** 逐跳头以及由 HttpClient/容器自行管理的头，不透传 */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
        "host", "connection", "content-length", "transfer-encoding", "upgrade",
        "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer", "expect");

    private static final Set<String> PASS_RESPONSE_HEADERS = Set.of(
        "content-type", "content-disposition", "cache-control", "x-accel-buffering", "etag", "last-modified");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final String sangongServiceUrl;

    public SangongServiceProxyController(
            @Value("${sangong.service-url:http://127.0.0.1:8088}") String sangongServiceUrl) {
        this.sangongServiceUrl = sangongServiceUrl.endsWith("/")
            ? sangongServiceUrl.substring(0, sangongServiceUrl.length() - 1)
            : sangongServiceUrl;
    }

    @RequestMapping(
        value = {PREFIX, PREFIX + "/**"},
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                  RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD})
    public ResponseEntity<StreamingResponseBody> proxy(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI().substring(PREFIX.length());
        if (path.isEmpty()) {
            path = "/";
        }
        String query = request.getQueryString();
        URI target = URI.create(sangongServiceUrl + path + (query != null ? "?" + query : ""));
        boolean sse = path.startsWith("/api/v1/admin/events/stream");

        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        if (!sse) {
            // SSE 长连接不设超时；普通请求 60s
            builder.timeout(Duration.ofSeconds(60));
        }

        String method = request.getMethod().toUpperCase();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] body = request.getInputStream().readAllBytes();
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
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

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("sangong-service proxy failed path={} target={} reason={}", path, target, e.toString());
            byte[] msg = "{\"ok\":false,\"error\":\"sangong service unavailable\"}"
                .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body(os -> os.write(msg));
        }

        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (PASS_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                values.forEach(v -> headers.add(name, v));
            }
        });

        InputStream upstream = response.body();
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
}
