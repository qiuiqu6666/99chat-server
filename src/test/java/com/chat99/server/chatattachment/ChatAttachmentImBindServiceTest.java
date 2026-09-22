package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentImBindServiceTest {

    @Mock ChatAttachmentReferenceRepository referenceRepository;
    @Mock ChatNativeVideoMessageRepository nativeVideoMessageRepository;
    @Mock ImUserIdService imUserIdService;

    @Test
    void afterSendConfirmsReservedReference() throws Exception {
        ChatAttachmentImBindService service = new ChatAttachmentImBindService(
            referenceRepository, nativeVideoMessageRepository, imUserIdService, new ObjectMapper());
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setReferenceId("ref_1");
        ref.setAttachmentId("att_1");
        ref.setOwnerUserId("u1");
        ref.setConversationType(ChatConversationType.c2c);
        ref.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        ref.setState(ChatReferenceState.reserved);
        when(referenceRepository.findByReferenceId("ref_1")).thenReturn(Optional.of(ref));
        when(imUserIdService.findBusinessUserId("u1")).thenReturn(Optional.of("u1"));
        when(imUserIdService.findBusinessUserId("u2")).thenReturn(Optional.of("u2"));

        String data = new ObjectMapper().writeValueAsString(Map.of(
            "type", "chat.attachment",
            "version", 1,
            "attachmentId", "att_1",
            "referenceId", "ref_1",
            "kind", "video",
            "name", "旅行.mp4"
        ));
        service.handleAfterSend(Map.of(
            "From_Account", "u1",
            "To_Account", "u2",
            "MsgKey", "msg_1",
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMCustomElem",
                "MsgContent", Map.of("Data", data)
            ))
        ));

        assertThat(ref.getState()).isEqualTo(ChatReferenceState.confirmed);
        assertThat(ref.getProviderMessageId()).isEqualTo("msg_1");
        assertThat(ref.getExpiresAt()).isNull();
    }

    @Test
    void afterSendConfirmsNativeVideoFromCloudCustomData() throws Exception {
        ChatAttachmentImBindService service = new ChatAttachmentImBindService(
            referenceRepository, nativeVideoMessageRepository, imUserIdService, new ObjectMapper());
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setReferenceId("ref_nv");
        ref.setAttachmentId("att_video");
        ref.setOwnerUserId("u1");
        ref.setConversationType(ChatConversationType.c2c);
        ref.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        ref.setState(ChatReferenceState.reserved);
        ChatNativeVideoMessage row = new ChatNativeVideoMessage();
        row.setOperationId("nvm_1");
        row.setSenderUserId("u1");
        row.setClientOperationId("task-1");
        row.setAttachmentId("att_video");
        row.setReferenceId("ref_nv");
        row.setStatus(ChatNativeVideoStatus.unknown);
        when(referenceRepository.findByReferenceId("ref_nv")).thenReturn(Optional.of(ref));
        when(nativeVideoMessageRepository.findBySenderUserIdAndClientOperationId("u1", "task-1"))
            .thenReturn(Optional.of(row));
        when(imUserIdService.findBusinessUserId("u1")).thenReturn(Optional.of("u1"));
        when(imUserIdService.findBusinessUserId("u2")).thenReturn(Optional.of("u2"));

        String cloud = new ObjectMapper().writeValueAsString(Map.of(
            "type", "chat.native-video",
            "version", 1,
            "clientOperationId", "task-1",
            "attachmentId", "att_video",
            "referenceId", "ref_nv"
        ));
        service.handleAfterSend(Map.of(
            "From_Account", "u1",
            "To_Account", "u2",
            "MsgKey", "msg_nv",
            "CloudCustomData", cloud,
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMVideoFileElem",
                "MsgContent", Map.of("VideoUUID", "att_video")
            ))
        ));

        assertThat(ref.getState()).isEqualTo(ChatReferenceState.confirmed);
        assertThat(row.getStatus()).isEqualTo(ChatNativeVideoStatus.sent);
        assertThat(row.getMsgKey()).isEqualTo("msg_nv");
    }
}
