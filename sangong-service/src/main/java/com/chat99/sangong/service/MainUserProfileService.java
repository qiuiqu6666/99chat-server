package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * 主服务用户资料客户端（Integration API），替代腾讯 IM portrait_get。
 * 昵称/头像带 30 分钟内存缓存。
 */
@Service
public class MainUserProfileService {
    private static final Logger log = LoggerFactory.getLogger(MainUserProfileService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long CACHE_TTL_SECONDS = 1800;
    private static final int MAX_BATCH = 100;

    private record CachedProfile(String nickname, String avatarUrl, long cachedAtEpoch) {}

    private final SangongProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();

    public MainUserProfileService(SangongProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    }

    public boolean isConfigured() {
        return props.getIntegration().getApiToken() != null
            && !props.getIntegration().getApiToken().isBlank()
            && props.getIntegration().getBaseUrl() != null
            && !props.getIntegration().getBaseUrl().isBlank();
    }

    /** 单用户昵称（未配置或查不到返回 null）。 */
    public String getNickname(String imUserId) {
        Map<String, Map<String, String>> out = getProfiles(List.of(imUserId));
        Map<String, String> profile = out.get(imUserId);
        if (profile == null) {
            return null;
        }
        String nickname = profile.get("nickname");
        return nickname == null || nickname.isBlank() ? null : nickname.trim();
    }

    /** 批量头像 URL（imUserId -> avatarUrl）。 */
    public Map<String, String> getAvatarUrls(List<String> imUserIds) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : getProfiles(imUserIds).entrySet()) {
            String avatar = e.getValue().get("avatarUrl");
            if (avatar != null && !avatar.isBlank()) {
                out.put(e.getKey(), avatar.trim());
            }
        }
        return out;
    }

    /** 批量资料（imUserId -> {nickname, avatarUrl}），缓存优先。 */
    public Map<String, Map<String, String>> getProfiles(List<String> imUserIds) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (imUserIds == null || imUserIds.isEmpty()) {
            return out;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : imUserIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        long now = Instant.now().getEpochSecond();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            CachedProfile cached = cache.get(id);
            if (cached != null && now - cached.cachedAtEpoch() <= CACHE_TTL_SECONDS) {
                out.put(id, toMap(cached));
            } else {
                missing.add(id);
            }
        }
        if (missing.isEmpty() || !isConfigured()) {
            return out;
        }
        for (int i = 0; i < missing.size(); i += MAX_BATCH) {
            List<String> chunk = missing.subList(i, Math.min(i + MAX_BATCH, missing.size()));
            fetchChunk(chunk, out, now);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void fetchChunk(List<String> chunk, Map<String, Map<String, String>> out, long now) {
        String baseUrl = props.getIntegration().getBaseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/integration/v1/users/profiles";
        try {
            Map<String, Object> body = Map.of("userIds", chunk);
            Request request = new Request.Builder()
                .url(url)
                .header("X-Integration-Token", props.getIntegration().getApiToken())
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful() || raw.isBlank()) {
                    log.warn("main profile fetch failed status={}", response.code());
                    return;
                }
                Map<String, Object> payload = mapper.readValue(raw, Map.class);
                // 主服务全局响应包装 {code,message,data:{...}}
                if (payload.get("data") instanceof Map<?, ?> data) {
                    payload = (Map<String, Object>) data;
                }
                Object profiles = payload.get("profiles");
                if (!(profiles instanceof List<?> list)) {
                    return;
                }
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> row)) continue;
                    String userId = str(row.get("userId"));
                    if (userId == null || userId.isBlank()) continue;
                    CachedProfile profile = new CachedProfile(
                        str(row.get("nickname")), str(row.get("avatarUrl")), now);
                    cache.put(userId, profile);
                    out.put(userId, toMap(profile));
                }
            }
        } catch (Exception e) {
            log.warn("main profile fetch exception: {}", e.getMessage());
        }
    }

    private static Map<String, String> toMap(CachedProfile profile) {
        Map<String, String> m = new LinkedHashMap<>();
        if (profile.nickname() != null) m.put("nickname", profile.nickname());
        if (profile.avatarUrl() != null) m.put("avatarUrl", profile.avatarUrl());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
