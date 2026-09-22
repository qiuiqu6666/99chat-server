package com.chat99.sangong.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chat99.sangong.tenant.TenantContext;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * 直接消费主服务 Kafka：按 IM groupId 解析租户后进入业务管道。
 * 群间数据互不互通（TenantContext）。
 */
@Service
@ConditionalOnProperty(name = "sangong.kafka.enabled", havingValue = "true")
public class ImKafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(ImKafkaConsumer.class);

    private final ImMessageService messages;
    private final TenantService tenants;
    private final ObjectMapper json = new ObjectMapper();

    public ImKafkaConsumer(ImMessageService messages, TenantService tenants) {
        this.messages = messages;
        this.tenants = tenants;
    }

    @KafkaListener(
        topics = "${sangong.kafka.topic-after-send:chat99.im.after-send}",
        groupId = "${sangong.kafka.consumer-group:sangong-im-consumer}")
    public void onAfterSend(ConsumerRecord<String, String> record) {
        Map<String, Object> event = parse(record.value());
        if (event == null || !"group".equals(str(event.get("chatType")))) {
            return;
        }
        String groupId = str(event.get("groupId"));
        var tenant = tenants.findActiveByGameGroup(groupId);
        if (tenant.isEmpty()) {
            return;
        }
        Map<String, Object> rawBody = parse(str(event.get("rawBody")));
        if (rawBody == null) {
            log.warn("kafka after-send missing rawBody msgKey={} groupId={}", event.get("msgKey"), groupId);
            return;
        }
        TenantContext.run(tenant.get().getTenantId(), () -> {
            try {
                ImMessageService.CallbackResult result = messages.handleSend(rawBody, true);
                if (result.status() >= 400) {
                    log.warn("kafka send handle failed groupId={} msgSeq={} status={} body={}",
                        groupId, event.get("msgSeq"), result.status(), result.body());
                } else {
                    log.info("kafka send handled tenant={} groupId={} msgSeq={} status={}",
                        tenant.get().getTenantId(), groupId, event.get("msgSeq"), result.status());
                }
            } catch (Exception e) {
                log.error("kafka send handle exception groupId={} msgSeq={} err={}",
                    groupId, event.get("msgSeq"), e.getMessage(), e);
            }
        });
    }

    @KafkaListener(
        topics = "${sangong.kafka.topic-group-recall:chat99.im.group-recall}",
        groupId = "${sangong.kafka.consumer-group:sangong-im-consumer}")
    public void onGroupRecall(ConsumerRecord<String, String> record) {
        Map<String, Object> rawBody = parse(record.value());
        if (rawBody == null) {
            return;
        }
        String groupId = str(rawBody.get("GroupId"));
        var tenant = tenants.findActiveByGameGroup(groupId);
        if (tenant.isEmpty()) {
            return;
        }
        TenantContext.run(tenant.get().getTenantId(), () -> {
            try {
                ImMessageService.CallbackResult result = messages.handleRecall(rawBody, true);
                if (result.status() >= 400) {
                    log.warn("kafka recall handle failed groupId={} status={} body={}",
                        groupId, result.status(), result.body());
                }
            } catch (Exception e) {
                log.error("kafka recall handle exception groupId={} err={}", groupId, e.getMessage(), e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            log.debug("kafka payload parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
