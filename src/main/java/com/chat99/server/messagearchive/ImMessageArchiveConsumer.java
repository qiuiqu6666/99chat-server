package com.chat99.server.messagearchive;

import com.chat99.server.call.CallWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public class ImMessageArchiveConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImMessageArchiveConsumer.class);

    private final MessageArchiveProperties props;
    private final ChatMessageWriteRepository writeRepository;
    private final ChatMessageTableRouter tableRouter;
    private final ImMessageArchiveProducer dlqProducer;
    private final CallWebhookService callWebhookService;
    private final ConversationRecentProjector recentProjector;
    private final ObjectMapper json;
    private final Map<String, Integer> retryCounts = new LinkedHashMap<>();

    public ImMessageArchiveConsumer(MessageArchiveProperties props,
                                    ChatMessageWriteRepository writeRepository,
                                    ChatMessageTableRouter tableRouter,
                                    ImMessageArchiveProducer dlqProducer,
                                    CallWebhookService callWebhookService,
                                    ConversationRecentProjector recentProjector,
                                    ObjectMapper json) {
        this.props = props;
        this.writeRepository = writeRepository;
        this.tableRouter = tableRouter;
        this.dlqProducer = dlqProducer;
        this.callWebhookService = callWebhookService;
        this.recentProjector = recentProjector;
        this.json = json;
    }

    @KafkaListener(
        topics = "${chat99.message-archive.kafka.topic-after-send}",
        groupId = "${chat99.message-archive.kafka.archive-consumer-group}",
        containerFactory = "archiveKafkaListenerContainerFactory")
    public void consume(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }
        List<ImMessageArchiveEvent> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                ImMessageArchiveEvent event = json.readValue(record.value(), ImMessageArchiveEvent.class);
                events.add(event);
            } catch (Exception e) {
                log.warn("im archive consumer skip invalid record offset={} err={}", record.offset(), e.getMessage());
            }
        }
        if (events.isEmpty()) {
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }

        Map<String, List<ImMessageArchiveEvent>> byTable = new LinkedHashMap<>();
        for (ImMessageArchiveEvent event : events) {
            String table = tableRouter.physicalTable(event.msgTimeMs());
            byTable.computeIfAbsent(table, k -> new ArrayList<>()).add(event);
        }

        try {
            for (Map.Entry<String, List<ImMessageArchiveEvent>> entry : byTable.entrySet()) {
                String table = entry.getKey();
                String suffix = table.substring("chat_message_".length());
                writeRepository.ensureTable(table, suffix);
                writeRepository.batchInsertIgnore(table, entry.getValue());
            }
            try {
                recentProjector.apply(events);
            } catch (Exception projectErr) {
                log.warn("conversation recent project failed count={} err={}",
                    events.size(), projectErr.toString());
            }
            mergeAvCallFromEvents(events);
            retryCounts.keySet().retainAll(events.stream().map(ImMessageArchiveEvent::msgKey).toList());
            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("im archive batch write failed count={} err={}", events.size(), e.getMessage());
            for (ImMessageArchiveEvent event : events) {
                int retries = retryCounts.merge(event.msgKey(), 1, Integer::sum);
                if (retries >= props.consumer().maxRetries()) {
                    dlqProducer.publishToDlq(event, retries);
                    writeRepository.insertFailLog(
                        event.msgKey(), event.rawBody(), e.getMessage(), retries);
                    retryCounts.remove(event.msgKey());
                }
            }
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }

    private void mergeAvCallFromEvents(List<ImMessageArchiveEvent> events) {
        for (ImMessageArchiveEvent event : events) {
            if (!ImMessageArchiveParser.CMD_C2C_AFTER.equals(event.callbackCommand())) {
                continue;
            }
            if (event.rawBody() == null || !event.rawBody().contains("av_call")) {
                continue;
            }
            try {
                var imBody = com.chat99.server.call.CallAvCallImBodyBuilder.fromRawBody(json, event.rawBody());
                if (imBody != null) {
                    callWebhookService.tryMergeFromImBody(imBody);
                }
            } catch (Exception e) {
                log.debug("av_call archive consumer merge skip msgKey={}: {}", event.msgKey(), e.getMessage());
            }
        }
    }
}
