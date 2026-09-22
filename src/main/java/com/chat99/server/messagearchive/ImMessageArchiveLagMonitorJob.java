package com.chat99.server.messagearchive;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.apache.kafka.clients.consumer.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
@ConditionalOnMessageArchiveWorker
public class ImMessageArchiveLagMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(ImMessageArchiveLagMonitorJob.class);
    private static final long LAG_WARN_THRESHOLD = 100_000L;

    private final MessageArchiveProperties props;
    private final ConsumerFactory<String, String> consumerFactory;
    /** 长驻监控 consumer（不 subscribe、不参与消费组 rebalance）；原实现每分钟新建/销毁一次。 */
    private Consumer<String, String> consumer;

    public ImMessageArchiveLagMonitorJob(MessageArchiveProperties props,
                                         @Autowired(required = false) ConsumerFactory<String, String> consumerFactory) {
        this.props = props;
        this.consumerFactory = consumerFactory;
    }

    @Scheduled(fixedDelayString = "${chat99.message-archive.lag-monitor-interval-ms:60000}")
    public synchronized void checkLag() {
        if (consumerFactory == null) {
            return;
        }
        try {
            if (consumer == null) {
                consumer = consumerFactory.createConsumer(props.kafka().archiveConsumerGroup(), "");
            }
            var partitions = consumer.partitionsFor(props.kafka().topicAfterSend());
            if (partitions == null || partitions.isEmpty()) {
                return;
            }
            var assignment = partitions.stream()
                .map(p -> new org.apache.kafka.common.TopicPartition(p.topic(), p.partition()))
                .toList();
            consumer.assign(assignment);
            var endOffsets = consumer.endOffsets(assignment);
            var committed = consumer.committed(new java.util.HashSet<>(assignment));
            long totalLag = 0;
            for (var tp : assignment) {
                long end = endOffsets.getOrDefault(tp, 0L);
                var meta = committed.get(tp);
                long current = meta == null ? 0L : meta.offset();
                totalLag += Math.max(0, end - current);
            }
            if (totalLag > LAG_WARN_THRESHOLD) {
                log.warn("im archive kafka lag high lag={} threshold={}", totalLag, LAG_WARN_THRESHOLD);
            }
        } catch (Exception e) {
            log.debug("im archive lag monitor skipped err={}", e.getMessage());
            closeQuietly();
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (consumer == null) {
            return;
        }
        try {
            consumer.close(Duration.ofSeconds(5));
        } catch (Exception ignored) {
            // best effort
        } finally {
            consumer = null;
        }
    }
}
