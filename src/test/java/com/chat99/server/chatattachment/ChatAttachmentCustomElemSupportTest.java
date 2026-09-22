package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatAttachmentCustomElemSupportTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void extractsV1Attachment() throws Exception {
        String data = json.writeValueAsString(Map.of(
            "type", "chat.attachment",
            "version", 1,
            "attachmentId", "att_1",
            "referenceId", "ref_1",
            "kind", "video",
            "name", "旅行.mp4",
            "sizeBytes", 268435456
        ));
        Map<String, Object> body = Map.of(
            "From_Account", "u1",
            "To_Account", "u2",
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMCustomElem",
                "MsgContent", Map.of("Data", data)
            ))
        );
        var msgs = ChatAttachmentCustomElemSupport.extract(body, json);
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).referenceId()).isEqualTo("ref_1");
        assertThat(ChatAttachmentCustomElemSupport.pushPreview(msgs.get(0))).contains("[视频]");
    }

    @Test
    void ignoresUnknownVersion() throws Exception {
        String data = json.writeValueAsString(Map.of(
            "type", "chat.attachment",
            "version", 2,
            "attachmentId", "att_1",
            "referenceId", "ref_1",
            "kind", "file",
            "name", "a.bin"
        ));
        Map<String, Object> body = Map.of("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data)
        )));
        assertThat(ChatAttachmentCustomElemSupport.extract(body, json)).isEmpty();
    }

    @Test
    void extractsNativeVideoCloudCustomData() throws Exception {
        String cloud = json.writeValueAsString(Map.of(
            "type", "chat.native-video",
            "version", 1,
            "clientOperationId", "task-1",
            "attachmentId", "att_video",
            "referenceId", "ref_conversation"
        ));
        var parsed = ChatAttachmentCustomElemSupport.extractNativeVideo(
            Map.of("CloudCustomData", cloud, "MsgBody", List.of(Map.of(
                "MsgType", "TIMVideoFileElem",
                "MsgContent", Map.of()
            ))), json);
        assertThat(parsed).isNotNull();
        assertThat(parsed.clientOperationId()).isEqualTo("task-1");
        assertThat(parsed.attachmentId()).isEqualTo("att_video");
        assertThat(parsed.referenceId()).isEqualTo("ref_conversation");
        assertThat(ChatAttachmentCustomElemSupport.extract(Map.of(
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMVideoFileElem",
                "MsgContent", Map.of()
            ))
        ), json)).isEmpty();
    }
}
