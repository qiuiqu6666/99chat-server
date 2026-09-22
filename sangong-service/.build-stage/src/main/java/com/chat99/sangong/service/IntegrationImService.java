package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 主服务撤回群消息（/integration/v1/im/messages/recall，fire-and-forget），不经外部集成服务。 */
@Service
public class IntegrationImService {
    private static final Logger log = LoggerFactory.getLogger(IntegrationImService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SangongProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntegrationImService(SangongProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    public boolean isConfigured() {
        return !props.getIntegration().getApiToken().isBlank()
            && !props.getIntegration().getBaseUrl().isBlank();
    }

    public Map<String, Object> recallGroupMessages(String groupId, List<Long> msgSeqList, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isConfigured()) {
            log.warn("integration recall skipped: token/base url not configured");
            out.put("ok", false);
            out.put("code", "INTEGRATION_NOT_CONFIGURED");
            return out;
        }
        if (groupId == null || groupId.isEmpty()) {
            out.put("ok", false);
            out.put("code", "GROUP_NOT_CONFIGURED");
            return out;
        }
        List<Long> seqs = new ArrayList<>(new LinkedHashSet<>(msgSeqList.stream().filter(s -> s != null && s > 0).toList()));
        if (seqs.isEmpty()) {
            out.put("ok", true);
            out.put("recalled", List.of());
            return out;
        }

        List<Long> recalled = new ArrayList<>();
        Map<Long, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < seqs.size(); i += 10) {
            List<Long> chunk = seqs.subList(i, Math.min(i + 10, seqs.size()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chatType", "group");
            body.put("groupId", groupId);
            body.put("msgSeqList", chunk);
            body.put("reason", reason);
            body.put("syncArchive", true);
            Map<String, Object> payload = postRecall(body);
            if (payload == null) {
                for (Long seq : chunk) errors.put(seq, "request_failed");
                continue;
            }
            if ("INTEGRATION_NOT_CONFIGURED".equals(payload.get("code"))) {
                out.put("ok", false);
                out.put("code", "INTEGRATION_NOT_CONFIGURED");
                return out;
            }
            // 主服务返回 imSuccess；旧契约用 ok。两者任一为真即成功。
            boolean ok = Boolean.TRUE.equals(payload.get("ok"))
                || Boolean.TRUE.equals(payload.get("imSuccess"));
            if (!ok) {
                String message = String.valueOf(payload.getOrDefault("message",
                    payload.getOrDefault("code", "recall_failed")));
                for (Long seq : chunk) errors.put(seq, message);
                continue;
            }
            recalled.addAll(chunk);
        }

        out.put("ok", errors.isEmpty());
        out.put("recalled", recalled);
        if (!errors.isEmpty()) {
            out.put("errors", errors);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postRecall(Map<String, Object> body) {
        String baseUrl = props.getIntegration().getBaseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/integration/v1/im/messages/recall";
        try {
            Request request = new Request.Builder()
                .url(url)
                .header("X-Integration-Token", props.getIntegration().getApiToken())
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                Object parsed = raw.isBlank() ? null : mapper.readValue(raw, Map.class);
                if (!(parsed instanceof Map)) {
                    log.warn("main recall invalid response, status={}", response.code());
                    return null;
                }
                Map<String, Object> payload = (Map<String, Object>) parsed;
                // 主服务全局响应包装 {code,message,data:{...}}，成功结果在 data 内
                if (payload.get("data") instanceof Map<?, ?> data) {
                    return (Map<String, Object>) data;
                }
                return payload;
            }
        } catch (Exception e) {
            log.warn("main recall exception: {}", e.getMessage());
            return null;
        }
    }
}
