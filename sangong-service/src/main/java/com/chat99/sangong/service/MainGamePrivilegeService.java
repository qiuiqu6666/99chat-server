package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 通过主服务实时校验登录 JWT 对应用户是否拥有 game_privileged。
 * 权限结果不缓存，确保后台撤销特权后下一次管理请求立即失效。
 */
@Service
public class MainGamePrivilegeService {
    private static final Logger log = LoggerFactory.getLogger(MainGamePrivilegeService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public enum Status {
        ALLOWED,
        UNAUTHORIZED,
        FORBIDDEN,
        UNAVAILABLE
    }

    public record CheckResult(Status status, String userId) {}

    private final SangongProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MainGamePrivilegeService(SangongProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    }

    @SuppressWarnings("unchecked")
    public CheckResult check(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return new CheckResult(Status.UNAUTHORIZED, null);
        }
        String baseUrl = props.getIntegration().getBaseUrl();
        String apiToken = props.getIntegration().getApiToken();
        if (baseUrl == null || baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            log.error("main game privilege check is not configured");
            return new CheckResult(Status.UNAVAILABLE, null);
        }

        String url = baseUrl.replaceAll("/+$", "")
            + "/integration/v1/users/game-privilege/check";
        Request request = new Request.Builder()
            .url(url)
            .header("X-Integration-Token", apiToken)
            .header("Authorization", authorization)
            .post(RequestBody.create("{}", JSON))
            .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 401) {
                return new CheckResult(Status.UNAUTHORIZED, null);
            }
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("main game privilege check failed status={}", response.code());
                return new CheckResult(Status.UNAVAILABLE, null);
            }
            String raw = response.body().string();
            Map<String, Object> payload = mapper.readValue(raw, Map.class);
            if (payload.get("data") instanceof Map<?, ?> data) {
                payload = (Map<String, Object>) data;
            }
            String userId = payload.get("userId") == null
                ? null : String.valueOf(payload.get("userId"));
            // 优先使用主服务有效特权；兼容旧字段 gamePrivileged
            boolean privileged = Boolean.TRUE.equals(payload.get("gameEnabledEffective"))
                || Boolean.TRUE.equals(payload.get("gamePrivileged"));
            return new CheckResult(privileged ? Status.ALLOWED : Status.FORBIDDEN, userId);
        } catch (Exception e) {
            log.warn("main game privilege check exception: {}", e.getMessage());
            return new CheckResult(Status.UNAVAILABLE, null);
        }
    }
}
