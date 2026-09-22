package com.chat99.server.messagearchive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
@ConditionalOnMessageArchiveWorker
public class ImMessageArchiveDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImMessageArchiveDlqConsumer.class);

    private final ChatMessageWriteRepository writeRepository;
    private final ChatMessageTableRouter tableRouter;
    private final ObjectMapper json;

    public ImMessageArchiveDlqConsumer(ChatMessageWriteRepository writeRepository,
                                       ChatMessageTableRouter tableRouter,
                                       ObjectMapper json) {
        this.writeRepository = writeRepository;
        this.tableRouter = tableRouter;
        this.json = json;
    }

    @KafkaListener(
        topics = "${chat99.message-archive.kafka.topic-dlq}",
        groupId = "${chat99.message-archive.kafka.archive-consumer-group}-dlq")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = json.readValue(record.value(), Map.class);
            Object eventRaw = envelope.get("event");
            ImMessageArchiveEvent event = json.convertValue(eventRaw, ImMessageArchiveEvent.class);
            String table = tableRouter.physicalTable(event.msgTimeMs());
            String suffix = table.substring("chat_message_".length());
            writeRepository.ensureTable(table, suffix);
            writeRepository.batchInsertIgnore(table, java.util.List.of(event));
            log.info("im archive dlq recovered msgKey={}", event.msgKey());
        } catch (Exception e) {
            log.error("im archive dlq consumer failed offset={} err={}", record.offset(), e.getMessage());
            return;
        }
        if (ack != null) {
            ack.acknowledge();
        }
    }
}
