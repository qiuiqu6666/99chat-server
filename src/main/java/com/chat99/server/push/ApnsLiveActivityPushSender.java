package com.chat99.server.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Live Activity Update/End Push。走普通 APNs 鉴权（p8/p12），topic 为 {@code {bundleId}.push-type.liveactivity}，
 * 与 VoIP Push 证书/通道分离。
 */
@Component
public class ApnsLiveActivityPushSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsLiveActivityPushSender.class);
    public static final String TOPIC_SUFFIX = ".push-type.liveactivity";
    private static final int MAX_COLLAPSE_ID_LEN = 64;

    private final PushProperties props;
    private final ApnsClientFactory clientFactory;

    public ApnsLiveActivityPushSender(PushProperties props, ApnsClientFactory clientFactory) {
        this.props = props;
        this.clientFactory = clientFactory;
    }

    public boolean isReady() {
        return clientFactory.isReady();
    }

    public PushSendResult send(String pushToken, String bundleId, String environment,
                               String payload, String collapseId, boolean endEvent) {
        if (!isReady()) {
            return PushSendResult.skipped("APNS_NOT_CONFIGURED");
        }
        if (pushToken == null || pushToken.isBlank()) {
            return PushSendResult.skipped("NO_LIVE_ACTIVITY_TOKEN");
        }
        try {
            boolean production = resolveProduction(environment);
            ApnsClient client = clientFactory.getClient(production);
            String topic = liveActivityTopic(bundleId);
            String token = toApnsDeviceToken(pushToken);
            Instant expireAt = Instant.now().plusSeconds(endEvent ? 120 : 3600);
            SimpleApnsPushNotification notification = new SimpleApnsPushNotification(
                token,
                topic,
                payload,
                expireAt,
                DeliveryPriority.IMMEDIATE,
                PushType.LIVE_ACTIVITY,
                ApnsPushSender.sanitizeCollapseId(collapseId),
                null);
            PushNotificationFuture<SimpleApnsPushNotification,
                com.eatthepath.pushy.apns.PushNotificationResponse<SimpleApnsPushNotification>> future =
                client.sendNotification(notification);
            var response = future.get();
            if (response.isAccepted()) {
                log.info(
                    "apns live-activity accepted topic={} env={} end={} apnsId={} status={}",
                    topic, production ? "production" : "development", endEvent,
                    response.getApnsId(), response.getStatusCode());
                return PushSendResult.ok();
            }
            String reason = response.getRejectionReason().orElse("REJECTED");
            boolean invalidToken = isInvalidTokenReason(reason);
            log.warn(
                "apns live-activity rejected topic={} env={} end={} apnsId={} status={} reason={}",
                topic, production ? "production" : "development", endEvent,
                response.getApnsId(), response.getStatusCode(), reason);
            return invalidToken ? PushSendResult.invalidToken(reason) : PushSendResult.failed(reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushSendResult.failed("INTERRUPTED");
        } catch (Exception e) {
            log.warn("apns live-activity send failed end={} err={}", endEvent, e.getMessage());
            return PushSendResult.failed(e.getMessage());
        }
    }

    public String liveActivityTopic(String bundleId) {
        String base = bundleId == null || bundleId.isBlank()
            ? props.apns().bundleId()
            : bundleId.trim();
        return base + TOPIC_SUFFIX;
    }

    public boolean resolveProduction(String environment) {
        if (environment == null || environment.isBlank()) {
            return props.apns().production();
        }
        String e = environment.trim().toLowerCase();
        if (e.startsWith("dev") || "sandbox".equals(e)) {
            return false;
        }
        return true;
    }

    static boolean isInvalidTokenReason(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("BadDeviceToken")
            || reason.contains("Unregistered")
            || reason.contains("ExpiredToken")
            || reason.contains("DeviceTokenNotForTopic");
    }

    /** ActivityKit token：十六进制原样；否则按 Base64 解码再转 hex。 */
    public static String toApnsDeviceToken(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim().replace(" ", "");
        if (t.startsWith("<") && t.endsWith(">") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).replace(" ", "");
        }
        if (isHex(t) && t.length() >= 64) {
            return t.toLowerCase();
        }
        byte[] decoded = tryBase64(t);
        if (decoded != null && decoded.length >= 16) {
            return HexFormat.of().formatHex(decoded);
        }
        return t;
    }

    private static boolean isHex(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tryBase64(String t) {
        try {
            return Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getUrlDecoder().decode(t);
            } catch (IllegalArgumentException ignoredAgain) {
                return null;
            }
        }
    }

    public static String collapseId(String orderId) {
        String id = orderId == null ? "wd" : "wd-" + orderId;
        return id.length() <= MAX_COLLAPSE_ID_LEN ? id : id.substring(0, MAX_COLLAPSE_ID_LEN);
    }
}
