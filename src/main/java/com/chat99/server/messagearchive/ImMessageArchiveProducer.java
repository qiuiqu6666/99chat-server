package com.chat99.server.messagearchive;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.im.ImCallbackVerifier;
import com.chat99.server.push.PushConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ImMessageArchiveProducer {

    private static final Logger log = LoggerFactory.getLogger(ImMessageArchiveProducer.class);

    private final MessageArchiveProperties archiveProps;
    private final ImMessageArchiveParser parser;
    private final ImCallbackVerifier callbackVerifier;
    private final PushConfigService pushConfig;
    private final AppSettingService settings;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;

    public ImMessageArchiveProducer(MessageArchiveProperties archiveProps,
                                    ImMessageArchiveParser parser,
                                    ImCallbackVerifier callbackVerifier,
                                    PushConfigService pushConfig,
                                    AppSettingService settings,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper json) {
        this.archiveProps = archiveProps;
        this.parser = parser;
        this.callbackVerifier = callbackVerifier;
        this.pushConfig = pushConfig;
        this.settings = settings;
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    public boolean isAfterSendCommand(String command) {
        return parser.isAfterSendCommand(command);
    }

    public void publishAfterSend(String sdkAppId,
                                 String command,
                                 String callbackToken,
                                 String sign,
                                 String requestTime,
                                 String rawBody) {
        verifyAuth(sdkAppId, callbackToken, sign, requestTime);
        Optional<ImMessageArchiveEvent> eventOpt = parser.parse(rawBody, command, sdkAppId);
        if (eventOpt.isEmpty()) {
            return;
        }
        ImMessageArchiveEvent event = eventOpt.get();
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_JSON");
        }
        String key = parser.partitionKey(event);
        ProducerRecord<String, String> record = new ProducerRecord<>(
            archiveProps.kafka().topicAfterSend(), key, payload);
        record.headers().add(new RecordHeader("X-Chat-Type", event.chatType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Msg-Key", event.msgKey().getBytes(StandardCharsets.UTF_8)));
        try {
            kafkaTemplate.send(record).get(archiveProps.webhookProducerTimeoutMs(), TimeUnit.MILLISECONDS);
            log.debug("im archive kafka sent msgKey={}", event.msgKey());
        } catch (Exception e) {
            log.error("im archive kafka send failed msgKey={} err={}", event.msgKey(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KAFKA_UNAVAILABLE");
        }
    }

    /**
     * 群撤回事件转发到 Kafka（原始腾讯回调 JSON），供下游（如 sangong）消费。
     * 失败仅记日志，不影响回调响应。
     */
    public void publishGroupRecall(String command, String rawBody) {
        if (!"Group.CallbackAfterRecallMsg".equals(command) || rawBody == null || rawBody.isBlank()) {
            return;
        }
        String groupId = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.readValue(rawBody, Map.class);
            Object gid = body.get("GroupId");
            groupId = gid == null ? null : gid.toString().trim();
        } catch (JsonProcessingException e) {
            return;
        }
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                archiveProps.kafka().topicGroupRecall(), groupId, rawBody);
            record.headers().add(new RecordHeader("X-Chat-Type", "group".getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(archiveProps.webhookProducerTimeoutMs(), TimeUnit.MILLISECONDS);
            log.info("im group recall kafka sent groupId={}", groupId);
        } catch (Exception e) {
            log.error("im group recall kafka send failed groupId={} err={}", groupId, e.getMessage());
        }
    }

    public void publishToDlq(ImMessageArchiveEvent event, int retryCount) {
        try {
            String payload = json.writeValueAsString(Map.of(
                "event", event,
                "retryCount", retryCount));
            kafkaTemplate.send(archiveProps.kafka().topicDlq(), event.msgKey(), payload);
        } catch (Exception e) {
            log.warn("im archive dlq send failed msgKey={} err={}", event.msgKey(), e.getMessage());
        }
    }

    private void verifyAuth(String sdkAppId, String callbackToken, String sign, String requestTime) {
        validateSdkAppId(sdkAppId);
        if (sign != null && !sign.isBlank()) {
            callbackVerifier.verifySignature(sign, requestTime);
        } else {
            callbackVerifier.verifyQueryToken(callbackToken);
        }
    }

    private void validateSdkAppId(String sdkAppId) {
        if (sdkAppId == null || sdkAppId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Set<String> allowed = allowedSdkAppIds();
        if (!allowed.isEmpty() && !allowed.contains(sdkAppId.trim())) {
            log.warn("im archive callback rejected sdkAppId={}", sdkAppId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private Set<String> allowedSdkAppIds() {
        String csv = pushConfig.getAllowedSdkAppIds();
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        }
        int configured = settings.getInt(AppSettingService.IM_SDK_APP_ID, 0);
        if (configured != 0) {
            return Set.of(String.valueOf(configured));
        }
        return Set.of();
    }
}
