package com.chat99.server.messagearchive;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImChatPushCallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

class ImChatPushKafkaConsumerTest {

    @Test
    void retriesBeforeAcknowledgingSuccessfulRecord() throws Exception {
        ImChatPushCallbackService pushService = mock(ImChatPushCallbackService.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ImMessageArchiveEvent event = event();
        doThrow(new IllegalStateException("temporary"))
            .doThrow(new IllegalStateException("temporary"))
            .doNothing()
            .when(pushService)
            .processAfterSend(event.sdkAppId(), event.callbackCommand(), event.rawBody());

        consumer(pushService, kafkaTemplate).consume(
            List.of(record(event)), acknowledgment);

        verify(pushService, times(3))
            .processAfterSend(event.sdkAppId(), event.callbackCommand(), event.rawBody());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void publishesExhaustedRecordToDedicatedDlqBeforeAcknowledging() throws Exception {
        ImChatPushCallbackService pushService = mock(ImChatPushCallbackService.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ImMessageArchiveEvent event = event();
        doThrow(new IllegalStateException("permanent"))
            .when(pushService)
            .processAfterSend(event.sdkAppId(), event.callbackCommand(), event.rawBody());
        when(kafkaTemplate.send(
            org.mockito.ArgumentMatchers.eq("chat99.im.push.dlq"),
            org.mockito.ArgumentMatchers.eq("msg-1"),
            anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        consumer(pushService, kafkaTemplate).consume(
            List.of(record(event)), acknowledgment);

        verify(pushService, times(3))
            .processAfterSend(event.sdkAppId(), event.callbackCommand(), event.rawBody());
        verify(kafkaTemplate).send(
            org.mockito.ArgumentMatchers.eq("chat99.im.push.dlq"),
            org.mockito.ArgumentMatchers.eq("msg-1"),
            anyString());
        verify(acknowledgment).acknowledge();
    }

    private static ImChatPushKafkaConsumer consumer(
        ImChatPushCallbackService pushService,
        KafkaTemplate<String, String> kafkaTemplate) {
        MessageArchiveProperties properties = new MessageArchiveProperties(
            true,
            500,
            10,
            new MessageArchiveProperties.Consumer(true, true, 500, 3, 4, 4),
            null,
            null,
            new MessageArchiveProperties.Kafka(
                "chat99.im.after-send",
                "chat99.im.archive.dlq",
                "chat99.im.push.dlq",
                "chat99.im.group-recall",
                "archive-group",
                "push-group"),
            60_000,
            null,
            null);
        return new ImChatPushKafkaConsumer(
            pushService, properties, kafkaTemplate, new ObjectMapper());
    }

    private static ConsumerRecord<String, String> record(ImMessageArchiveEvent event)
        throws Exception {
        return new ConsumerRecord<>(
            "chat99.im.after-send", 2, 10L, "msg-1",
            new ObjectMapper().writeValueAsString(event));
    }

    private static ImMessageArchiveEvent event() {
        return new ImMessageArchiveEvent(
            "event-1",
            "1400000000",
            "C2C.CallbackAfterSendMsg",
            "msg-1",
            "c2c",
            "sender",
            "receiver",
            null,
            null,
            1L,
            "TIMTextElem",
            "hello",
            "[]",
            "{}",
            1L,
            null);
    }
}
