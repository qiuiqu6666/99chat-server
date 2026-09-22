package com.chat99.server.robot;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 将请求原样转发到内网 robot-service。无条件注册，供切流模式下的入口复用。
 */
@Component
public class RobotServiceHttpProxy {

    private static final Logger log = LoggerFactory.getLogger(RobotServiceHttpProxy.class);

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
        "host", "connection", "content-length", "transfer-encoding", "upgrade",
        "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer", "expect");

    private static final Set<String> PASS_RESPONSE_HEADERS = Set.of(
        "content-type", "content-disposition", "cache-control");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final String robotServiceUrl;

    public RobotServiceHttpProxy(
            @Value("${robot.service-url:http://127.0.0.1:8091}") String robotServiceUrl) {
        this.robotServiceUrl = robotServiceUrl.endsWith("/")
            ? robotServiceUrl.substring(0, robotServiceUrl.length() - 1)
            : robotServiceUrl;
    }

    public ResponseEntity<byte[]> forward(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        URI target = URI.create(robotServiceUrl + path + (query != null ? "?" + query : ""));

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(60));

        if ("POST".equalsIgnoreCase(request.getMethod())) {
            byte[] body = request.getInputStream().readAllBytes();
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            builder.GET();
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

        try {
            HttpResponse<byte[]> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach((name, values) -> {
                if (PASS_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                    values.forEach(v -> headers.add(name, v));
                }
            });
            return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("robot-service proxy failed path={} target={} reason={}", path, target, e.toString());
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"robot service unavailable\"}"
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
