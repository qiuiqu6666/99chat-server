package com.chat99.server.push;

import com.chat99.server.call.CallUserIdNormalizer;
import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApnsVoipPushSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsVoipPushSender.class);

    private final PushProperties props;
    private final ApnsClientFactory clientFactory;
    private final ObjectMapper json = new ObjectMapper();

    public ApnsVoipPushSender(PushProperties props, ApnsClientFactory clientFactory) {
        this.props = props;
        this.clientFactory = clientFactory;
    }

    public boolean isReady() {
        return clientFactory.isVoipReady();
    }

    public PushSendResult send(UserPushToken token, VoipCallPush call) {
        if (!isReady()) {
            return PushSendResult.skipped("APNS_NOT_CONFIGURED");
        }
        if (token.getVoipPushToken() == null || token.getVoipPushToken().isBlank()) {
            return PushSendResult.skipped("NO_VOIP_TOKEN");
        }
        try {
            ApnsClient client = clientFactory.getVoipClient();
            String payload = buildPayload(call);
            String topic = voipTopic();
            SimpleApnsPushNotification notification = new SimpleApnsPushNotification(
                token.getVoipPushToken(),
                topic,
                payload,
                // A delayed VoIP invite is no longer a live call. Keep APNs
                // from replaying it when the phone reconnects much later.
                Instant.now().plusSeconds(5),
                DeliveryPriority.IMMEDIATE,
                PushType.VOIP);
            PushNotificationFuture<SimpleApnsPushNotification, com.eatthepath.pushy.apns.PushNotificationResponse<SimpleApnsPushNotification>> future =
                client.sendNotification(notification);
            var response = future.get();
            if (response.isAccepted()) {
                log.info(
                    "apns voip accepted userId={} deviceId={} status={} apnsId={} topic={} environment={}",
                    token.getUserId(), token.getDeviceId(), response.getStatusCode(),
                    response.getApnsId(), topic, apnsEnvironment());
                return PushSendResult.ok();
            }
            String reason = response.getRejectionReason().orElse("REJECTED");
            boolean invalidToken = reason.contains("BadDeviceToken")
                || reason.contains("Unregistered")
                || reason.contains("DeviceTokenNotForTopic");
            log.warn(
                "apns voip rejected userId={} deviceId={} status={} apnsId={} topic={} environment={} reason={}",
                token.getUserId(), token.getDeviceId(), response.getStatusCode(),
                response.getApnsId(), topic, apnsEnvironment(), reason);
            return invalidToken ? PushSendResult.invalidToken(reason) : PushSendResult.failed(reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushSendResult.failed("INTERRUPTED");
        } catch (Exception e) {
            log.warn("apns voip send failed userId={} deviceId={} err={}",
                token.getUserId(), token.getDeviceId(), e.getMessage());
            return PushSendResult.failed(e.getMessage());
        }
    }

    private String voipTopic() {
        String bundleId = props.apns().bundleId();
        String suffix = props.apns().voipTopicSuffix();
        if (suffix == null || suffix.isBlank()) {
            suffix = ".voip";
        }
        if (!suffix.startsWith(".")) {
            suffix = "." + suffix;
        }
        return bundleId + suffix;
    }

    private String apnsEnvironment() {
        return props.apns().production() ? "production" : "development";
    }

    String buildPayload(VoipCallPush call) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", Map.of("content-available", 1));
        Map<String, Object> data = new LinkedHashMap<>();
        String pushType = call.type() == null || call.type().isBlank() ? "av_call" : call.type();
        data.put("type", pushType);
        data.put("action", call.action() == null || call.action().isBlank() ? "invite" : call.action());
        data.put("inviteId", call.inviteId());
        data.put("callId", call.inviteId());
        data.put("callerId", call.callerId());
        String callerName = call.callerName();
        if (!CallUserIdNormalizer.isValidVoipDisplayName(callerName, call.callerId())) {
            callerName = call.callerId();
        }
        data.put("callerName", callerName == null ? "" : callerName);
        data.put("calleeId", call.calleeId());
        data.put("mediaType", call.mediaType());
        if (call.isTerminal()) {
            data.put("call_end", 1);
        }
        if (call.callerAvatarUrl() != null && !call.callerAvatarUrl().isBlank()) {
            data.put("callerAvatarUrl", call.callerAvatarUrl());
        }
        if (call.roomId() != null && !call.roomId().isBlank()) {
            data.put("roomId", call.roomId());
            if ("lk_call".equals(pushType)) {
                data.put("roomName", call.roomId());
            }
        }
        root.put("data", data);
        // Compatibility for clients released before nested `data` was introduced.
        root.putAll(data);
        return json.writeValueAsString(root);
    }
}
