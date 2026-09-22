package com.chat99.server.lifepayment.yuanren;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class YuanrenApiClient {

    private static final Logger log = LoggerFactory.getLogger(YuanrenApiClient.class);
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded; charset=utf-8");

    private final YuanrenProperties props;
    private final ObjectMapper json;
    private final OkHttpClient http;

    public YuanrenApiClient(YuanrenProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(Math.max(1, props.getConnectTimeoutMs()), TimeUnit.MILLISECONDS)
            .readTimeout(Math.max(1, props.getReadTimeoutMs()), TimeUnit.MILLISECONDS)
            .build();
    }

    public record RechargeResult(
        boolean ok,
        String errno,
        String errmsg,
        String orderNumber,
        String outTradeNum,
        String totalPrice,
        String title,
        String raw) {}

    public record CheckItem(
        String orderNumber,
        String outTradeNum,
        String mobile,
        String productId,
        String state,
        String chargeAmount,
        String chargeKami,
        String raw) {}

    public RechargeResult recharge(Map<String, String> businessParams) {
        Map<String, String> body = new LinkedHashMap<>(businessParams);
        body.put("userid", props.getUserid());
        body.put("notify_url", props.getNotifyUrl());
        Map<String, String> signed = YuanrenSignSupport.withSign(body, props.getApikey());
        JsonNode root = post("/index/recharge", signed);
        String errno = text(root, "errno");
        String errmsg = text(root, "errmsg");
        JsonNode data = root.path("data");
        boolean ok = "0".equals(errno);
        return new RechargeResult(
            ok,
            errno,
            errmsg,
            text(data, "order_number"),
            text(data, "out_trade_num"),
            text(data, "total_price"),
            text(data, "title"),
            root.toString());
    }

    public CheckItem checkOne(String outTradeNum) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("userid", props.getUserid());
        body.put("out_trade_nums", outTradeNum);
        Map<String, String> signed = YuanrenSignSupport.withSign(body, props.getApikey());
        JsonNode root = post("/index/check", signed);
        if (!"0".equals(text(root, "errno"))) {
            throw new IllegalStateException("yuanren check failed: " + text(root, "errmsg"));
        }
        JsonNode data = root.path("data");
        // 文档 data 既可能是单对象，也可能是数组；兼容两种
        JsonNode item = data;
        if (data.isArray()) {
            if (data.isEmpty()) {
                throw new IllegalStateException("yuanren check empty");
            }
            item = data.get(0);
            for (JsonNode n : data) {
                if (outTradeNum.equals(text(n, "out_trade_num"))) {
                    item = n;
                    break;
                }
            }
        }
        return new CheckItem(
            text(item, "order_number"),
            text(item, "out_trade_num"),
            text(item, "mobile"),
            text(item, "product_id"),
            text(item, "state"),
            text(item, "charge_amount"),
            text(item, "charge_kami"),
            root.toString());
    }

    private JsonNode post(String path, Map<String, String> form) {
        String url = trimSlash(props.getBaseUrl()) + path;
        String encoded = YuanrenSignSupport.formBody(form);
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(encoded, FORM))
            .header("Accept", "application/json")
            .build();
        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.warn("yuanren http status={} path={} body={}", response.code(), path, abbreviate(respBody));
                throw new IllegalStateException("yuanren http " + response.code());
            }
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("yuanren empty body");
            }
            return json.readTree(respBody);
        } catch (IOException e) {
            throw new IllegalStateException("yuanren io: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trimSlash(String base) {
        if (base == null) {
            return "";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
