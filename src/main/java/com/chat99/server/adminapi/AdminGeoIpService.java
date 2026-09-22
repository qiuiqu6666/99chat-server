package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminGeoIpService {

    private static final Logger log = LoggerFactory.getLogger(AdminGeoIpService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);
    private static final int CACHE_MAX = 4096;

    private final ObjectMapper json;
    private final HttpClient http;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public AdminGeoIpService(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    public String lookupRegion(String ip) {
        String normalized = normalizeIp(ip);
        if (normalized == null) {
            return "—";
        }
        if (isPrivateIp(normalized)) {
            return "内网";
        }
        String cached = cache.get(normalized);
        if (cached != null) {
            return cached;
        }
        String resolved = fetchFromIpApi(normalized);
        if (resolved == null || resolved.isBlank()) {
            resolved = "—";
        }
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(normalized, resolved);
        return resolved;
    }

    private String fetchFromIpApi(String ip) {
        try {
            String url = "http://ip-api.com/json/" + ip
                + "?lang=zh-CN&fields=status,country,regionName,city";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return null;
            }
            JsonNode root = json.readTree(resp.body());
            if (!"success".equalsIgnoreCase(root.path("status").asText())) {
                return null;
            }
            return joinRegionParts(
                textOrNull(root.get("country")),
                textOrNull(root.get("regionName")),
                textOrNull(root.get("city")));
        } catch (Exception e) {
            log.debug("geoip lookup failed ip={} {}", ip, e.getMessage());
            return null;
        }
    }

    private static String joinRegionParts(String country, String region, String city) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, country);
        if (region != null && !region.equals(country)) {
            appendPart(sb, region);
        }
        if (city != null && !city.equals(region) && !city.equals(country)) {
            appendPart(sb, city);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(part.trim());
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String trimmed = ip.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.matches("^[0-9a-fA-F:.]+$")) {
            return null;
        }
        return trimmed;
    }

    static boolean isPrivateIp(String ip) {
        if (ip == null) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "0.0.0.0".equals(ip) || "::1".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        String lower = ip.toLowerCase(Locale.ROOT);
        return lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80");
    }

    public void validateLookupIp(String ip) {
        if (normalizeIp(ip) == null) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid ip");
        }
    }
}
