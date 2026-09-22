package com.chat99.server.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CatFee energy rental API client.
 * Signing / path construction aligned with official Java sample:
 * https://docs.catfee.io/getting-started/buy-energy-via-api-on-catfee/java
 */
@Service
public class CatFeeClient {

    private static final Logger log = LoggerFactory.getLogger(CatFeeClient.class);
    private static final RequestBody EMPTY_BODY = RequestBody.create(new byte[0], null);
    private static final String CONFIRMED = "DELEGATION_CONFIRMED";

    private final WalletConfigService configService;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper json = new ObjectMapper();

    public CatFeeClient(WalletConfigService configService) {
        this.configService = configService;
    }

    public record EnergyOrder(String id, String confirmStatus, String status) {}

    public boolean isConfigured() {
        return configService.isCatfeeConfigured();
    }

    public Optional<EnergyOrder> rentEnergy(String receiver, String clientOrderId) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        int quantity = configService.getCatfeeEnergyQuantity();
        String duration = configService.getCatfeeEnergyDuration();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("quantity", String.valueOf(quantity));
        query.put("receiver", receiver);
        query.put("duration", duration);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            query.put("client_order_id", sanitizeClientOrderId(clientOrderId));
        }
        // Official sample signs the raw (unencoded) query string.
        String requestPath = buildRequestPath("/v1/order", query);
        String url = trimTrailingSlash(configService.getCatfeeBaseUrl()) + requestPath;
        JsonNode root = postSigned(requestPath, url);
        return parseOrder(root);
    }

    public Optional<EnergyOrder> getOrder(String orderId) {
        if (!isConfigured() || orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        String requestPath = "/v1/order/" + orderId.trim();
        String url = trimTrailingSlash(configService.getCatfeeBaseUrl()) + requestPath;
        JsonNode root = getSigned(requestPath, url);
        return parseOrder(root);
    }

    public boolean waitForEnergy(String orderId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 5000L);
        while (System.currentTimeMillis() < deadline) {
            Optional<EnergyOrder> order = getOrder(orderId);
            if (order.isPresent() && CONFIRMED.equalsIgnoreCase(order.get().confirmStatus())) {
                return true;
            }
            sleepQuietly(2000L);
        }
        Optional<EnergyOrder> last = getOrder(orderId);
        boolean ok = last.isPresent() && CONFIRMED.equalsIgnoreCase(last.get().confirmStatus());
        if (!ok) {
            log.warn("CatFee energy order not confirmed in time id={} last={}", orderId, last.orElse(null));
        }
        return ok;
    }

    public boolean rentEnergyAndWait(String receiver, String clientOrderId) {
        Optional<EnergyOrder> created = rentEnergy(receiver, clientOrderId);
        if (created.isEmpty()) {
            return false;
        }
        if (CONFIRMED.equalsIgnoreCase(created.get().confirmStatus())) {
            return true;
        }
        return waitForEnergy(created.get().id(), configService.getCatfeeOrderTimeoutMs());
    }

    private Optional<EnergyOrder> parseOrder(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        if (!isBizSuccess(root)) {
            log.warn("CatFee business error code={} msg={}",
                text(root, "code"), text(root, "msg"));
            return Optional.empty();
        }
        JsonNode data = root.has("data") ? root.get("data") : root;
        if (data == null || data.isNull()) {
            return Optional.empty();
        }
        String id = text(data, "id");
        if (id == null || id.isBlank()) {
            log.warn("CatFee order response missing id body={}", truncate(root.toString(), 300));
            return Optional.empty();
        }
        return Optional.of(new EnergyOrder(
            id,
            text(data, "confirm_status"),
            text(data, "status")));
    }

    /** Docs/examples use both numeric 0 and string "0". */
    private static boolean isBizSuccess(JsonNode root) {
        if (root == null || !root.has("code") || root.get("code").isNull()) {
            // Some payloads may omit code when data is present; accept if data.id exists later.
            return true;
        }
        JsonNode code = root.get("code");
        if (code.isNumber()) {
            return code.asInt() == 0;
        }
        String s = code.asText("").trim();
        return "0".equals(s);
    }

    private JsonNode postSigned(String requestPath, String url) {
        return exchange("POST", requestPath, url, true);
    }

    private JsonNode getSigned(String requestPath, String url) {
        return exchange("GET", requestPath, url, false);
    }

    private JsonNode exchange(String method, String requestPath, String url, boolean post) {
        try {
            // Official sample: Instant.now().toString() (ISO-8601 UTC)
            String timestamp = Instant.now().toString();
            String sign = sign(timestamp, method, requestPath, configService.getCatfeeApiSecret());
            Request.Builder rb = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "99chat-catfee-client/1.0")
                .header("CF-ACCESS-KEY", configService.getCatfeeApiKey())
                .header("CF-ACCESS-SIGN", sign)
                .header("CF-ACCESS-TIMESTAMP", timestamp);
            if (post) {
                // Official Java sample uses noBody(); empty body (not "{}")
                rb.post(EMPTY_BODY);
            } else {
                rb.get();
            }
            try (Response resp = http.newCall(rb.build()).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.warn("CatFee {} {} -> {} body={}", method, requestPath, resp.code(), truncate(body, 500));
                    return null;
                }
                if (body.isBlank()) {
                    log.warn("CatFee {} {} -> {} empty body", method, requestPath, resp.code());
                    return null;
                }
                // Cloudflare / WAF plain-text blocks (e.g. "error code: 1010")
                if (!body.trim().startsWith("{") && !body.trim().startsWith("[")) {
                    log.warn("CatFee {} {} -> {} non-json body={}", method, requestPath, resp.code(), truncate(body, 500));
                    return null;
                }
                return json.readTree(body);
            }
        } catch (Exception e) {
            log.warn("CatFee {} {} err={}", method, requestPath, e.getMessage());
            return null;
        }
    }

    /**
     * Official sample concatenates {@code key=value} without URL-encoding.
     * Do not put reserved characters in values (sanitize client_order_id).
     */
    static String buildRequestPath(String path, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }
        String queryString = queryParams.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&", "?", ""));
        return path + queryString;
    }

    static String sign(String timestamp, String method, String requestPath, String secret) throws Exception {
        String payload = timestamp + method.toUpperCase() + requestPath;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Keep idempotency key URL/sign-safe (no ':' which OkHttp would encode as %3A). */
    static String sanitizeClientOrderId(String raw) {
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_-]", "-");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        return cleaned;
    }

    private static String trimTrailingSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        String t = base.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
