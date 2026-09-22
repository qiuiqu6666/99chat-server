package com.chat99.server.im.restqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class ImRestJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImRestJobConsumer.class);

    private final ObjectMapper json;
    private final ImRestJobHandler handler;
    private final ImRestQueuePublisher publisher;
    private final ImRestQueueProperties props;

    public ImRestJobConsumer(ObjectMapper json,
                             ImRestJobHandler handler,
                             ImRestQueuePublisher publisher,
                             ImRestQueueProperties props) {
        this.json = json;
        this.handler = handler;
        this.publisher = publisher;
        this.props = props;
    }

    @KafkaListener(
        topics = "${chat99.im.rest-queue.topic:chat99.im.rest.jobs}",
        groupId = "${chat99.im.rest-queue.consumer-group:chat99-im-rest-worker}",
        containerFactory = "imRestQueueKafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ImRestJob job = null;
        try {
            job = json.readValue(record.value(), ImRestJob.class);
            ImRestJobHandler.Outcome outcome = handler.handle(job);
            switch (outcome) {
                case DONE -> acknowledge(ack);
                case RETRY -> {
                    if (requeueOrDead(job)) {
                        acknowledge(ack);
                    }
                }
                case DEAD -> {
                    if (publisher.publishToDlq(job, "DEAD")) {
                        acknowledge(ack);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("im rest job consume failed key={} err={}", record.key(), e.getMessage());
            if (job != null) {
                if (requeueOrDead(job)) {
                    acknowledge(ack);
                }
            } else {
                if (publisher.publishToDlq(
                    new ImRestJob(
                        null, ImRestJob.Type.VERIFY_GROUP_EXISTS, null, null, null, 0, 0L, "PARSE_FAIL",
                        null, null, null, null),
                    e.getMessage())) {
                    acknowledge(ack);
                }
            }
        }
    }

    private static void acknowledge(Acknowledgment ack) {
        if (ack != null) {
            ack.acknowledge();
        }
    }

    private boolean requeueOrDead(ImRestJob job) {
        int next = job.attempt() + 1;
        if (next >= props.maxAttempts()) {
            return publisher.publishToDlq(job.withAttempt(next), "MAX_ATTEMPTS");
        }
        try {
            Thread.sleep(Math.min(5_000L, 200L * next));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return publisher.enqueueRetry(job.withAttempt(next));
    }
}
