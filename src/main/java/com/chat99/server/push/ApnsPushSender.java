package com.chat99.server.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.util.ApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApnsPushSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushSender.class);
    private static final int MAX_COLLAPSE_ID_LEN = 64;

    private final PushProperties props;
    private final ApnsClientFactory clientFactory;

    public ApnsPushSender(PushProperties props, ApnsClientFactory clientFactory) {
        this.props = props;
        this.clientFactory = clientFactory;
    }

    public boolean isReady() {
        return clientFactory.isReady();
    }

    public PushSendResult send(UserPushToken token, PushMessage message) {
        if (!isReady()) {
            return PushSendResult.skipped("APNS_NOT_CONFIGURED");
        }
        try {
            ApnsClient client = clientFactory.getClient();
            String payload = buildPayload(message);
            String collapseId = sanitizeCollapseId(message.collapseId());
            String topic = props.apns().bundleId();
            log.info("apns payload userId={} deviceId={} topic={} collapseId={} payload={}",
                token.getUserId(), token.getDeviceId(), topic, collapseId, payload);
            SimpleApnsPushNotification notification = new SimpleApnsPushNotification(
                token.getPushToken(),
                topic,
                payload,
                Instant.now().plusSeconds(3600),
                DeliveryPriority.IMMEDIATE,
                PushType.ALERT,
                collapseId,
                null);
            PushNotificationFuture<SimpleApnsPushNotification, com.eatthepath.pushy.apns.PushNotificationResponse<SimpleApnsPushNotification>> future =
                client.sendNotification(notification);
            var response = future.get();
            if (response.isAccepted()) {
                log.info(
                    "apns accepted userId={} deviceId={} status={} apnsId={} topic={} environment={}",
                    token.getUserId(), token.getDeviceId(), response.getStatusCode(),
                    response.getApnsId(), topic, apnsEnvironment());
                return PushSendResult.ok();
            }
            String reason = response.getRejectionReason().orElse("REJECTED");
            boolean invalidToken = reason.contains("BadDeviceToken")
                || reason.contains("Unregistered")
                || reason.contains("DeviceTokenNotForTopic");
            log.warn(
                "apns rejected userId={} deviceId={} status={} apnsId={} topic={} environment={} reason={}",
                token.getUserId(), token.getDeviceId(), response.getStatusCode(),
                response.getApnsId(), topic, apnsEnvironment(), reason);
            return invalidToken ? PushSendResult.invalidToken(reason) : PushSendResult.failed(reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushSendResult.failed("INTERRUPTED");
        } catch (Exception e) {
            log.warn("apns send failed userId={} deviceId={} err={}",
                token.getUserId(), token.getDeviceId(), e.getMessage());
            return PushSendResult.failed(e.getMessage());
        }
    }

    private String apnsEnvironment() {
        return props.apns().production() ? "production" : "development";
    }

    private String buildPayload(PushMessage message) {
        return buildPayloadJson(message);
    }

    /** APNs payload：avatarUrl 与 aps 同级，含 avatarUrl 时设 mutable-content 供 NSE 下载头像。 */
    static String buildPayloadJson(PushMessage message) {
        ApnsPayloadBuilder builder = new SimpleApnsPayloadBuilder();
        builder.setAlertTitle(message.title());
        builder.setAlertBody(message.body());
        builder.setSound("default");
        String avatarUrl = message.data().get("avatarUrl");
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            builder.setMutableContent(true);
            builder.addCustomProperty("avatarUrl", avatarUrl.trim());
        }
        if (message.threadId() != null) {
            builder.setThreadId(message.threadId());
        }
        for (Map.Entry<String, String> entry : message.data().entrySet()) {
            if ("avatarUrl".equals(entry.getKey())) {
                continue;
            }
            builder.addCustomProperty(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    static String sanitizeCollapseId(String collapseId) {
        if (collapseId == null || collapseId.isBlank()) {
            return null;
        }
        String trimmed = collapseId.trim();
        return trimmed.length() <= MAX_COLLAPSE_ID_LEN
            ? trimmed
            : trimmed.substring(0, MAX_COLLAPSE_ID_LEN);
    }
}
