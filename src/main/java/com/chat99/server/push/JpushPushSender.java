package com.chat99.server.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JpushPushSender {

    private static final Logger log = LoggerFactory.getLogger(JpushPushSender.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final PushConfigService pushConfig;
    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    public JpushPushSender(PushConfigService pushConfig) {
        this.pushConfig = pushConfig;
    }

    public boolean isReady() {
        if (!pushConfig.isJpushEnabled()) {
            return false;
        }
        String appKey = pushConfig.getJpushAppKey();
        String masterSecret = pushConfig.getJpushMasterSecret();
        return appKey != null && !appKey.isBlank()
            && masterSecret != null && !masterSecret.isBlank();
    }

    public PushSendResult send(UserPushToken token, PushMessage message) {
        return sendBatch(List.of(token.getPushToken()), message, token.getUserId(), token.getDeviceId());
    }

    public PushSendResult sendBatch(List<String> registrationIds, PushMessage message, String userId, String deviceId) {
        if (!isReady()) {
            return PushSendResult.skipped("JPUSH_NOT_CONFIGURED");
        }
        if (registrationIds == null || registrationIds.isEmpty()) {
            return PushSendResult.skipped("NO_REGISTRATION_ID");
        }
        try {
            Map<String, Object> body = buildPushBody(registrationIds, message, pushConfig.getJpushThirdPartyConfig());
            return postPush(body, userId, deviceId);
        } catch (Exception e) {
            log.warn("jpush send failed userId={} deviceId={} err={}", userId, deviceId, e.getMessage());
            return PushSendResult.failed(e.getMessage());
        }
    }

    /** Android 透传（无通知栏），供提现进度在 App 被杀后更新前台通知。 */
    public PushSendResult sendDataMessage(List<String> registrationIds, Map<String, String> extras, String userId) {
        if (!isReady()) {
            return PushSendResult.skipped("JPUSH_NOT_CONFIGURED");
        }
        if (registrationIds == null || registrationIds.isEmpty()) {
            return PushSendResult.skipped("NO_REGISTRATION_ID");
        }
        try {
            return postPush(buildDataMessageBody(registrationIds, extras), userId, "data");
        } catch (Exception e) {
            log.warn("jpush data send failed userId={} err={}", userId, e.getMessage());
            return PushSendResult.failed(e.getMessage());
        }
    }

    private PushSendResult postPush(Map<String, Object> body, String userId, String deviceId) throws Exception {
        String appKey = pushConfig.getJpushAppKey().trim();
        String masterSecret = pushConfig.getJpushMasterSecret().trim();
        String payload = json.writeValueAsString(body);
        String url = pushConfig.getJpushBaseUrl().replaceAll("/+$", "") + "/v3/push";
        String auth = Base64.getEncoder().encodeToString(
            (appKey + ":" + masterSecret).getBytes(StandardCharsets.UTF_8));
        Request request = new Request.Builder()
            .url(url)
            .addHeader("Authorization", "Basic " + auth)
            .post(RequestBody.create(payload, JSON))
            .build();
        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                return PushSendResult.ok();
            }
            boolean invalidToken = response.code() == 400
                && (respBody.contains("1003") || respBody.contains("registration_id"));
            log.warn("jpush failed userId={} deviceId={} code={} body={}",
                userId, deviceId, response.code(), respBody);
            return invalidToken
                ? PushSendResult.invalidToken("HTTP_" + response.code())
                : PushSendResult.failed("HTTP_" + response.code());
        }
    }

    public static Map<String, Object> buildDataMessageBody(List<String> registrationIds, Map<String, String> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", List.of("android"));
        body.put("audience", Map.of("registration_id", registrationIds));
        Map<String, Object> message = new LinkedHashMap<>();
        String type = extras == null ? "wallet_withdraw_progress" : extras.getOrDefault("type", "wallet_withdraw_progress");
        message.put("msg_content", type);
        message.put("content_type", "text");
        message.put("extras", extras == null ? Map.of() : extras);
        body.put("message", message);
        return body;
    }

    static Map<String, Object> buildPushBody(List<String> registrationIds, PushMessage message,
                                             JpushThirdPartyConfig thirdParty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", List.of("android"));
        body.put("audience", Map.of("registration_id", registrationIds));
        body.put("notification", Map.of(
            "alert", message.body(),
            "android", buildAndroidNotification(message)));
        Map<String, Object> options = buildOptions(thirdParty);
        if (!options.isEmpty()) {
            body.put("options", options);
        }
        return body;
    }

    static Map<String, Object> buildOptions(JpushThirdPartyConfig thirdParty) {
        if (thirdParty == null || !thirdParty.enabled()) {
            return Map.of();
        }
        Map<String, Object> channels = new LinkedHashMap<>();
        putDistributionChannel(channels, "huawei", thirdParty.huaweiDistribution());
        putDistributionChannel(channels, "honor", thirdParty.honorDistribution());
        putDistributionChannel(channels, "oppo", thirdParty.oppoDistribution());
        putDistributionChannel(channels, "vivo", thirdParty.vivoDistribution());
        putXiaomiChannel(channels, thirdParty);
        if (channels.isEmpty()) {
            return Map.of();
        }
        return Map.of("third_party_channel", channels);
    }

    private static void putXiaomiChannel(Map<String, Object> channels, JpushThirdPartyConfig thirdParty) {
        String channelId = thirdParty.xiaomiChannelId();
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        Map<String, Object> xiaomi = new LinkedHashMap<>();
        xiaomi.put("distribution", normalizeDistribution(thirdParty.xiaomiDistribution()));
        xiaomi.put("channel_id", channelId.trim());
        channels.put("xiaomi", xiaomi);
    }

    private static void putDistributionChannel(Map<String, Object> channels, String vendor, String distribution) {
        String normalized = normalizeDistribution(distribution);
        if (normalized == null) {
            return;
        }
        channels.put(vendor, Map.of("distribution", normalized));
    }

    private static String normalizeDistribution(String distribution) {
        if (distribution == null || distribution.isBlank()) {
            return null;
        }
        return distribution.trim();
    }

    static Map<String, Object> buildAndroidNotification(PushMessage message) {
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("title", message.title());
        android.put("alert", message.body());
        Map<String, Object> extras = new LinkedHashMap<>(message.data());
        // JPush Android 不支持 notification.android.group；会话分组键放 extras 供客户端归组。
        if (message.threadId() != null) {
            extras.putIfAbsent("threadId", message.threadId());
        }
        android.put("extras", extras);
        String avatarUrl = message.data().get("avatarUrl");
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            android.put("large_icon", avatarUrl.trim());
        }
        return android;
    }
}
