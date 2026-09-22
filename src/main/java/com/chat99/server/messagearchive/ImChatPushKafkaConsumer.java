package com.chat99.server.messagearchive;

import com.chat99.server.im.ImChatPushCallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "chat99.message-archive.consumer",
    name = "push-enabled",
    havingValue = "true",
    matchIfMissing = false)
@ConditionalOnMessageArchiveWorker
public class ImChatPushKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImChatPushKafkaConsumer.class);

    private final ImChatPushCallbackService pushCallbackService;
    private final MessageArchiveProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;

    public ImChatPushKafkaConsumer(ImChatPushCallbackService pushCallbackService,
                                   MessageArchiveProperties properties,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper json) {
        this.pushCallbackService = pushCallbackService;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    @KafkaListener(
        topics = "${chat99.message-archive.kafka.topic-after-send}",
        groupId = "${chat99.message-archive.kafka.push-consumer-group}",
        containerFactory = "pushKafkaListenerContainerFactory")
    public void consume(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }
        for (ConsumerRecord<String, String> record : records) {
            processWithRetry(record);
        }
        if (ack != null) {
            ack.acknowledge();
        }
    }

    private void processWithRetry(ConsumerRecord<String, String> record) {
        int maxAttempts = Math.max(1, properties.consumer().maxRetries());
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ImMessageArchiveEvent event = json.readValue(record.value(), ImMessageArchiveEvent.class);
                pushCallbackService.processAfterSend(
                    event.sdkAppId(), event.callbackCommand(), event.rawBody());
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("im push kafka attempt failed topic={} partition={} offset={} attempt={}/{} err={}",
                    record.topic(), record.partition(), record.offset(), attempt, maxAttempts, e.getMessage());
            }
        }
        publishToDlq(record, maxAttempts, lastError);
    }

    private void publishToDlq(ConsumerRecord<String, String> record, int attempts, Exception error) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("sourceTopic", record.topic());
            envelope.put("sourcePartition", record.partition());
            envelope.put("sourceOffset", record.offset());
            envelope.put("sourceKey", record.key());
            envelope.put("attempts", attempts);
            envelope.put("error", error == null ? "UNKNOWN" : error.getMessage());
            envelope.put("recordValue", record.value());
            String payload = json.writeValueAsString(envelope);
            kafkaTemplate.send(properties.kafka().topicPushDlq(), record.key(), payload)
                .get(5, TimeUnit.SECONDS);
            log.error("im push kafka moved to dlq topic={} partition={} offset={} attempts={} err={}",
                record.topic(), record.partition(), record.offset(), attempts,
                error == null ? "UNKNOWN" : error.getMessage());
        } catch (Exception dlqError) {
            throw new IllegalStateException(
                "IM push failed and DLQ publish failed; batch must not be acknowledged", dlqError);
        }
    }
}
