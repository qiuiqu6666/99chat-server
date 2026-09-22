package com.chat99.server.grouplive;

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
 * 群直播统一入口：外部只访问主服务，主服务把 /group-live/** 原样转发到内网 group-live-service。
 * 例：GET /group-live/api/v1/health → http://127.0.0.1:8092/api/v1/health。
 */
@ConditionalOnProperty(name = "group-live.proxy-enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class GroupLiveServiceProxyController {

    private static final Logger log = LoggerFactory.getLogger(GroupLiveServiceProxyController.class);

    private static final String PREFIX = "/group-live";

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
        "host", "connection", "content-length", "transfer-encoding", "upgrade",
        "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer", "expect");

    private static final Set<String> PASS_RESPONSE_HEADERS = Set.of(
        "content-type", "content-disposition", "cache-control", "x-accel-buffering", "etag", "last-modified");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final String groupLiveServiceUrl;

    public GroupLiveServiceProxyController(
            @Value("${group-live.service-url:http://127.0.0.1:8092}") String groupLiveServiceUrl) {
        this.groupLiveServiceUrl = groupLiveServiceUrl.endsWith("/")
            ? groupLiveServiceUrl.substring(0, groupLiveServiceUrl.length() - 1)
            : groupLiveServiceUrl;
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
        URI target = URI.create(groupLiveServiceUrl + path + (query != null ? "?" + query : ""));

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(60));

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
            log.error("group-live-service proxy failed path={} target={} reason={}", path, target, e.toString());
            byte[] msg = "{\"ok\":false,\"error\":\"group-live service unavailable\"}"
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
