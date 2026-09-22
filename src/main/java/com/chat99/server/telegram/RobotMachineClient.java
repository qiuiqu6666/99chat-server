package com.chat99.server.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 主服务 → robot-service 机器码开群特权 HTTP 客户端。 */
@Component
public class RobotMachineClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String probeSecret;

    public RobotMachineClient(
            ObjectMapper objectMapper,
            @Value("${robot.service-url:http://127.0.0.1:8091}") String serviceUrl,
            @Value("${chat99.telegram.probe-secret:}") String probeSecret) {
        this.objectMapper = objectMapper;
        this.baseUrl = serviceUrl.endsWith("/")
            ? serviceUrl.substring(0, serviceUrl.length() - 1)
            : serviceUrl;
        this.probeSecret = probeSecret == null ? "" : probeSecret;
    }

    public Map<String, Object> bindGroup(String machineCode, String groupId) {
        return postJson(
            "/api/internal/robot-machines/bind-group",
            Map.of("groupId", groupId),
            Map.of("X-Machine-Code", machineCode));
    }

    public Map<String, Object> enableForGroup(String groupId, String robotId) {
        return postJson(
            "/api/internal/robot-machines/enable-for-group",
            Map.of("groupId", groupId, "robotId", robotId),
            Map.of("X-Probe-Secret", probeSecret));
    }

    public Map<String, Object> groupStatus(String groupId) {
        String q = URLEncoder.encode(groupId, StandardCharsets.UTF_8);
        return getJson("/api/internal/robot-machines/group-status?groupId=" + q,
            Map.of("X-Probe-Secret", probeSecret));
    }

    private Map<String, Object> postJson(String path, Object body, Map<String, String> headers) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            headers.forEach(builder::header);
            HttpResponse<String> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return parse(response);
        } catch (Exception e) {
            throw new IllegalStateException("robot-service call failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> getJson(String pathAndQuery, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(15))
                .GET();
            headers.forEach(builder::header);
            HttpResponse<String> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return parse(response);
        } catch (Exception e) {
            throw new IllegalStateException("robot-service call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(HttpResponse<String> response) throws Exception {
        JsonNode node = objectMapper.readTree(response.body() == null || response.body().isBlank()
            ? "{}" : response.body());
        Map<String, Object> map = objectMapper.convertValue(node, Map.class);
        if (response.statusCode() >= 400) {
            String msg = node.path("message").asText("HTTP " + response.statusCode());
            String code = node.path("code").asText("");
            throw new IllegalStateException(code.isBlank() ? msg : code + ": " + msg);
        }
        return map;
    }
}
