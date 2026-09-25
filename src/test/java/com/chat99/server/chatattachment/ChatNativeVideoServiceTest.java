package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatNativeVideoServiceTest {

    @Mock ChatAttachmentProperties props;
    @Mock ChatAttachmentAuthService authService;
    @Mock ChatAttachmentRepository attachmentRepository;
    @Mock ChatAttachmentReferenceRepository referenceRepository;
    @Mock ChatNativeVideoMessageRepository messageRepository;
    @Mock ChatNativeVideoPersistence persistence;
    @Mock ChatNativeVideoMediaService mediaService;
    @Mock ChatAttachmentOssClient oss;
    @Mock ImAdminClient imAdmin;
    @Mock ImUserIdService imUserIdService;
    @Mock ChatMediaProbeService mediaProbeService;

    private ChatNativeVideoDurationMetrics durationMetrics;
    private ChatNativeVideoService service;

    @BeforeEach
    void setUp() {
        durationMetrics = new ChatNativeVideoDurationMetrics();
        service = new ChatNativeVideoService(
            props, authService, attachmentRepository, referenceRepository, messageRepository,
            persistence, mediaService, oss, imAdmin, imUserIdService,
            mediaProbeService, durationMetrics, new ObjectMapper());
    }

    @Test
    void sameKeyDifferentTargetIsConflict() {
        stubReadyVideoAndThumb();
        ChatNativeVideoMessage existing = new ChatNativeVideoMessage();
        existing.setOperationId("nvm_1");
        existing.setSenderUserId("u1");
        existing.setClientOperationId("task-1");
        existing.setAttachmentId("att_other");
        existing.setReferenceId("ref_conversation");
        existing.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        existing.setStatus(ChatNativeVideoStatus.pending);
        when(messageRepository.findForUpdate("u1", "task-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.prepare("u1", new ChatNativeVideoService.SendRequest(
            "task-1", "att_video", "ref_conversation", "c2c", "u2", null, null), "https://apiios.99chat.vip"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(imAdmin);
    }

    @Test
    void sentSameKeyDoesNotDeliverAgain() {
        stubReadyVideoAndThumb();
        ChatNativeVideoMessage existing = new ChatNativeVideoMessage();
        existing.setOperationId("nvm_1");
        existing.setSenderUserId("u1");
        existing.setClientOperationId("task-1");
        existing.setAttachmentId("att_video");
        existing.setReferenceId("ref_conversation");
        existing.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        existing.setStatus(ChatNativeVideoStatus.sent);
        existing.setMsgKey("mk_1");
        when(messageRepository.findForUpdate("u1", "task-1")).thenReturn(Optional.of(existing));

        ChatNativeVideoService.Prep prep = service.prepare("u1", new ChatNativeVideoService.SendRequest(
            "task-1", "att_video", "ref_conversation", "c2c", "u2", null, null), "https://apiios.99chat.vip");
        assertThat(prep.deliverNow()).isFalse();
        assertThat(prep.row().getMsgKey()).isEqualTo("mk_1");
    }

    @Test
    void getDoesNotSend() {
        ChatNativeVideoMessage row = new ChatNativeVideoMessage();
        row.setClientOperationId("task-1");
        row.setAttachmentId("att_video");
        row.setReferenceId("ref_conversation");
        row.setStatus(ChatNativeVideoStatus.unknown);
        when(messageRepository.findBySenderUserIdAndClientOperationId("u1", "task-1"))
            .thenReturn(Optional.of(row));
        MapViewAssert(service.get("u1", "task-1"));
        verifyNoInteractions(imAdmin);
    }

    @Test
    void deliverUsesCeilSecondsFromDurationMs() {
        ChatNativeVideoMessage row = pendingRow();
        ChatAttachment video = readyVideo(120_000L);
        ChatAttachment thumb = readyThumb();
        stubDeliver(row, video, thumb);
        when(imAdmin.sendNativeVideo(anyBoolean(), anyString(), anyString(), anyInt(), any(), anyString()))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, "mk_1", 1L, 0));

        service.deliver("nvm_1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> content = ArgumentCaptor.forClass(Map.class);
        verify(imAdmin).sendNativeVideo(eq(true), eq("im-u1"), eq("im-u2"), eq(9), content.capture(), anyString());
        assertThat(content.getValue().get("VideoSecond")).isEqualTo(120L);
        assertThat(content.getValue().get("ThumbWidth")).isEqualTo(1080);
        assertThat(content.getValue().get("ThumbHeight")).isEqualTo(1920);
        assertThat(durationMetrics.snapshot().get("client")).isEqualTo(1L);
    }

    @Test
    void permissionRevokedAfterPrepareDoesNotMakeMediaPublicOrSend() {
        ChatNativeVideoMessage row = pendingRow();
        stubDeliver(row, readyVideo(1_000L), readyThumb());
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
            "CONVERSATION_FORBIDDEN"))
            .when(authService).requireSendConversation(anyString(), any());

        service.deliver("nvm_1");

        verify(persistence).mark("nvm_1", ChatNativeVideoStatus.failed,
            "CONVERSATION_FORBIDDEN", null, null);
        verify(oss, never()).setPublicRead(anyString());
        verifyNoInteractions(imAdmin);
    }

    @Test
    void revokedReferenceAfterPrepareDoesNotMakeMediaPublicOrSend() {
        ChatNativeVideoMessage row = pendingRow();
        stubDeliver(row, readyVideo(1_000L), readyThumb());
        ChatAttachmentReference revoked = authorizedReference();
        revoked.setState(ChatReferenceState.revoked);
        when(referenceRepository.findByReferenceId("ref_conversation"))
            .thenReturn(Optional.of(revoked));

        service.deliver("nvm_1");

        verify(persistence).mark("nvm_1", ChatNativeVideoStatus.failed,
            "ACCESS_DENIED", null, null);
        verify(oss, never()).setPublicRead(anyString());
        verifyNoInteractions(imAdmin);
    }

    @Test
    void unknownProviderOutcomeIsNotAutomaticallySentAgain() {
        ChatNativeVideoMessage row = pendingRow();
        row.setStatus(ChatNativeVideoStatus.unknown);
        when(messageRepository.findForUpdate("u1", "task-1"))
            .thenReturn(Optional.of(row));
        stubReadyVideoAndThumb();

        ChatNativeVideoService.Prep prep = service.prepare("u1",
            new ChatNativeVideoService.SendRequest("task-1", "att_video",
                "ref_conversation", "c2c", "u2", null, null),
            "https://apiios.99chat.vip");

        assertThat(prep.deliverNow()).isFalse();
        verifyNoInteractions(imAdmin);
    }

    @Test
    void losingTheDurableDispatchPermitCannotPublishMediaOrSend() {
        stubDeliver(pendingRow(), readyVideo(1_000L), readyThumb());
        when(persistence.beginDispatch("nvm_1")).thenReturn(false);

        service.deliver("nvm_1");

        verify(oss, never()).setPublicRead(anyString());
        verifyNoInteractions(imAdmin);
    }

    @Test
    void mediaAccessFailureDoesNotReportSendOrCallIm() {
        stubDeliver(pendingRow(), readyVideo(1_000L), readyThumb());
        org.mockito.Mockito.doThrow(new IllegalStateException("ACL unavailable"))
            .when(oss).setPublicRead("thumb-key");

        service.deliver("nvm_1");

        verify(persistence).mark("nvm_1", ChatNativeVideoStatus.failed,
            "MEDIA_ACCESS_UNAVAILABLE", null, null);
        verify(imAdmin, never()).sendNativeVideo(anyBoolean(), anyString(), anyString(),
            anyInt(), any(), anyString());
    }

    @Test
    void authorizationUnavailableDefersWithoutPublishingOrRejecting() {
        stubDeliver(pendingRow(), readyVideo(1_000L), readyThumb());
        org.mockito.Mockito.doThrow(new IllegalStateException("group store unavailable"))
            .when(authService).requireSendConversation(anyString(), any());

        service.deliver("nvm_1");

        verifyNoInteractions(persistence);
        verify(oss, never()).setPublicRead(anyString());
        verifyNoInteractions(imAdmin);
    }

    @Test
    void permissionRevokedDuringProbePreventsDispatchAfterTheSecondCheck() {
        stubDeliver(pendingRow(), readyVideo(1_000L), readyThumb());
        org.mockito.Mockito.doNothing()
            .doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "CONVERSATION_FORBIDDEN"))
            .when(authService).requireSendConversation(anyString(), any());

        service.deliver("nvm_1");

        verify(persistence, never()).beginDispatch(anyString());
        verify(oss, never()).setPublicRead(anyString());
        verifyNoInteractions(imAdmin);
    }

    @Test
    void deliverFallbackUsesVideoSecondZero() {
        ChatNativeVideoMessage row = pendingRow();
        ChatAttachment video = readyVideo(null);
        ChatAttachment thumb = readyThumb();
        stubDeliver(row, video, thumb);
        when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imAdmin.sendNativeVideo(anyBoolean(), anyString(), anyString(), anyInt(), any(), anyString()))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, "mk_1", 1L, 0));

        service.deliver("nvm_1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> content = ArgumentCaptor.forClass(Map.class);
        verify(imAdmin).sendNativeVideo(eq(true), eq("im-u1"), eq("im-u2"), eq(9), content.capture(), anyString());
        assertThat(content.getValue().get("VideoSecond")).isEqualTo(0L);
        assertThat(durationMetrics.snapshot().get("fallback")).isEqualTo(1L);
    }

    @Test
    void deliverRetriesVideoSecondOneWhenZeroRejected() {
        ChatNativeVideoMessage row = pendingRow();
        ChatAttachment video = readyVideo(null);
        ChatAttachment thumb = readyThumb();
        stubDeliver(row, video, thumb);
        when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        java.util.List<Object> seconds = new java.util.ArrayList<>();
        java.util.List<String> clouds = new java.util.ArrayList<>();
        when(imAdmin.sendNativeVideo(anyBoolean(), anyString(), anyString(), anyInt(), any(), anyString()))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = inv.getArgument(4);
                seconds.add(body.get("VideoSecond"));
                clouds.add(inv.getArgument(5));
                if (seconds.size() == 1) {
                    throw new ImRestException("invalid VideoSecond", 80001);
                }
                return new ImAdminClient.NativeVideoSendResult(true, "mk_1", 1L, 0);
            });

        service.deliver("nvm_1");

        assertThat(seconds).containsExactly(0L, 1L);
        assertThat(clouds.get(0)).doesNotContain("durationUnknown");
        assertThat(clouds.get(1)).contains("durationUnknown");
        verify(imAdmin, org.mockito.Mockito.times(2))
            .sendNativeVideo(eq(true), eq("im-u1"), eq("im-u2"), eq(9), any(), anyString());
    }

    @Test
    void prepareOverridesDurationFromRequest() {
        stubReadyVideoAndThumb();
        when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ChatNativeVideoMessage existing = new ChatNativeVideoMessage();
        existing.setOperationId("nvm_1");
        existing.setSenderUserId("u1");
        existing.setClientOperationId("task-1");
        existing.setAttachmentId("att_video");
        existing.setReferenceId("ref_conversation");
        existing.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        existing.setStatus(ChatNativeVideoStatus.sent);
        existing.setMsgKey("mk_1");
        when(messageRepository.findForUpdate("u1", "task-1")).thenReturn(Optional.of(existing));

        service.prepare("u1", new ChatNativeVideoService.SendRequest(
            "task-1", "att_video", "ref_conversation", "c2c", "u2", null, 125_400L),
            "https://apiios.99chat.vip");

        ArgumentCaptor<ChatAttachment> saved = ArgumentCaptor.forClass(ChatAttachment.class);
        verify(attachmentRepository).save(saved.capture());
        assertThat(saved.getValue().getDurationMs()).isEqualTo(125_400L);
        assertThat(saved.getValue().getMediaProbeSource()).isEqualTo(ChatMetadataProvenance.client);
    }

    private ChatNativeVideoMessage pendingRow() {
        ChatNativeVideoMessage row = new ChatNativeVideoMessage();
        row.setOperationId("nvm_1");
        row.setSenderUserId("u1");
        row.setPeerUserId("u2");
        row.setAttachmentId("att_video");
        row.setClientOperationId("task-1");
        row.setReferenceId("ref_conversation");
        row.setConversationType(ChatConversationType.c2c);
        row.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        row.setStatus(ChatNativeVideoStatus.pending);
        row.setImRandom(9);
        row.setMediaBaseUrl("https://image.99chat.vip");
        return row;
    }

    private ChatAttachment readyVideo(Long durationMs) {
        ChatAttachment video = new ChatAttachment();
        video.setAttachmentId("att_video");
        video.setOwnerUserId("u1");
        video.setKind(ChatAttachmentKind.video);
        video.setStatus(ChatAttachmentStatus.ready);
        video.setObjectKey("video-key");
        video.setThumbnailAttachmentId("att_thumb");
        video.setSizeBytes(127_000_000L);
        video.setDurationMs(durationMs);
        video.setOriginalName("clip.mp4");
        if (durationMs != null) {
            video.setMediaProbeSource(ChatMetadataProvenance.client);
        }
        return video;
    }

    private ChatAttachment readyThumb() {
        ChatAttachment thumb = new ChatAttachment();
        thumb.setAttachmentId("att_thumb");
        thumb.setOwnerUserId("u1");
        thumb.setParentAttachmentId("att_video");
        thumb.setKind(ChatAttachmentKind.image);
        thumb.setStatus(ChatAttachmentStatus.ready);
        thumb.setObjectKey("thumb-key");
        thumb.setSizeBytes(48_000L);
        thumb.setWidth(1080);
        thumb.setHeight(1920);
        thumb.setMimeType("image/jpeg");
        thumb.setOriginalName("thumbnail.jpg");
        return thumb;
    }

    private void stubDeliver(ChatNativeVideoMessage row, ChatAttachment video, ChatAttachment thumb) {
        lenient().when(props.nativeVideoMessageEnabled()).thenReturn(true);
        lenient().when(props.sendEnabled()).thenReturn(true);
        lenient().when(persistence.beginDispatch("nvm_1")).thenReturn(true);
        lenient().when(messageRepository.findById("nvm_1")).thenReturn(Optional.of(row));
        lenient().when(attachmentRepository.findByAttachmentId("att_video")).thenReturn(Optional.of(video));
        lenient().when(attachmentRepository.findByAttachmentId("att_thumb")).thenReturn(Optional.of(thumb));
        lenient().when(referenceRepository.findByReferenceId("ref_conversation"))
            .thenReturn(Optional.of(authorizedReference()));
        lenient().when(mediaProbeService.fillIfMissing(video)).thenReturn(video);
        lenient().when(mediaService.videoUrl(video, "https://image.99chat.vip")).thenReturn("https://cdn/v.mp4");
        lenient().when(mediaService.thumbUrl(thumb, "https://image.99chat.vip")).thenReturn("https://cdn/t.jpg");
        lenient().when(imUserIdService.requireImUserId("u1")).thenReturn("im-u1");
        lenient().when(imUserIdService.requireImUserId("u2")).thenReturn("im-u2");
    }

    private ChatAttachmentReference authorizedReference() {
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setReferenceId("ref_conversation");
        ref.setAttachmentId("att_video");
        ref.setOwnerUserId("u1");
        ref.setConversationType(ChatConversationType.c2c);
        ref.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        ref.setState(ChatReferenceState.reserved);
        return ref;
    }

    private void MapViewAssert(java.util.Map<String, Object> view) {
        assertThat(view.get("status")).isEqualTo("unknown");
        assertThat(view).doesNotContainKey("messageType");
        assertThat(view).doesNotContainKey("msgKey");
    }

    private void stubReadyVideoAndThumb() {
        ChatAttachment video = new ChatAttachment();
        video.setAttachmentId("att_video");
        video.setOwnerUserId("u1");
        video.setKind(ChatAttachmentKind.video);
        video.setStatus(ChatAttachmentStatus.ready);
        video.setObjectKey("video-key");
        video.setThumbnailAttachmentId("att_thumb");
        video.setSizeBytes(127_000_000L);
        video.setDurationMs(80_000L);
        ChatAttachment thumb = new ChatAttachment();
        thumb.setAttachmentId("att_thumb");
        thumb.setStatus(ChatAttachmentStatus.ready);
        thumb.setObjectKey("thumb-key");
        thumb.setSizeBytes(48_000L);
        thumb.setWidth(1080);
        thumb.setHeight(1920);
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setReferenceId("ref_conversation");
        ref.setAttachmentId("att_video");
        ref.setOwnerUserId("u1");
        ref.setConversationKey(ChatAttachmentConversationIds.c2c("u1", "u2").conversationKey());
        ref.setState(ChatReferenceState.reserved);
        when(attachmentRepository.findByAttachmentId("att_video")).thenReturn(Optional.of(video));
        when(attachmentRepository.findByAttachmentId("att_thumb")).thenReturn(Optional.of(thumb));
        when(referenceRepository.findByReferenceId("ref_conversation")).thenReturn(Optional.of(ref));
        when(oss.exists("video-key")).thenReturn(true);
        when(oss.exists("thumb-key")).thenReturn(true);
    }
}
