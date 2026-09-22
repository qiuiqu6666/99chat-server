package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class ChatAttachmentJsonCompatTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        assertThat(mapper.getDeserializationConfig().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
            .isFalse();
        ChatUploadController.InitBody init = mapper.readValue("""
            {
              "clientUploadKey": "task-1",
              "conversationType": "c2c",
              "peerUserId": "u2",
              "kind": "video",
              "nativeMessageKind": "video",
              "originalName": "a.mp4",
              "mimeType": "video/mp4",
              "declaredSizeBytes": 123,
              "durationMs": "abc",
              "width": 0.5,
              "height": null,
              "futureField": true
            }
            """, ChatUploadController.InitBody.class);
        assertThat(init.durationMs()).isNull();
        assertThat(init.width()).isNull();
        assertThat(init.height()).isNull();
        assertThat(init.clientUploadKey()).isEqualTo("task-1");

        ChatNativeVideoController.SendBody send = mapper.readValue("""
            {
              "clientOperationId": "task-1",
              "attachmentId": "att_1",
              "referenceId": "ref_1",
              "conversationType": "c2c",
              "peerUserId": "u2",
              "durationMs": 125000,
              "unknown": 1
            }
            """, ChatNativeVideoController.SendBody.class);
        assertThat(send.durationMs()).isEqualTo(125000L);
        assertThat(send.attachmentId()).isEqualTo("att_1");
    }
}
