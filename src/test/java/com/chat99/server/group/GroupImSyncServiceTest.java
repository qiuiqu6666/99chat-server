package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class GroupImSyncServiceTest {

    @Mock
    ImAdminClient im;
    @Mock
    ImUserIdService imUserIdService;
    @Mock
    ObjectProvider<ImRestQueuePublisher> queuePublisher;
    @Mock
    ImRestQueuePublisher publisher;

    GroupImSyncService service;

    @BeforeEach
    void setUp() {
        service = new GroupImSyncService(im, imUserIdService, queuePublisher, new ObjectMapper());
    }

    @Test
    void trySyncGroupFaceUrl_success_doesNotEnqueue() {
        service.trySyncGroupFaceUrl("g1", "https://x/a.png");

        verify(im).modifyGroupFaceUrl("g1", "https://x/a.png");
        verify(queuePublisher, never()).getIfAvailable();
    }

    @Test
    void trySyncGroupFaceUrl_failure_enqueues() {
        when(queuePublisher.getIfAvailable()).thenReturn(publisher);
        doThrow(new ImRestException("IM_REST_ERROR", 1))
            .when(im).modifyGroupFaceUrl("g1", "u");

        service.trySyncGroupFaceUrl("g1", "u");

        verify(publisher).enqueueGroupModifyFaceUrl(eq("g1"), eq("u"), eq("IM_REST_ERROR:1"));
    }

    @Test
    void trySyncAddMembers_mapsToImAccounts() {
        when(imUserIdService.toIm("a")).thenReturn("im_a");
        when(imUserIdService.toIm("b")).thenReturn("im_b");
        when(queuePublisher.getIfAvailable()).thenReturn(publisher);

        service.trySyncAddMembers("g1", List.of("a", "b"));

        verify(im).addGroupMembers("g1", List.of("im_a", "im_b"), false);
        verify(publisher).enqueueReconcileGroupUsers("g1", List.of("a", "b"), "im_sync_add");
        verify(publisher).enqueueSyncUserJoined("a", "im_sync_add");
        verify(publisher).enqueueSyncUserJoined("b", "im_sync_add");
    }

    @Test
    void trySyncDeleteMembers_success_enqueuesReconcile() {
        when(imUserIdService.toIm("a")).thenReturn("im_a");
        when(queuePublisher.getIfAvailable()).thenReturn(publisher);

        service.trySyncDeleteMembers("g1", List.of("a"));

        verify(im).deleteGroupMembers("g1", List.of("im_a"), false);
        verify(publisher).enqueueReconcileGroupUsers("g1", List.of("a"), "im_sync_delete");
        verify(publisher).enqueueSyncUserJoined("a", "im_sync_delete");
    }

    @Test
    void trySyncDeleteMembers_failure_throws() {
        when(imUserIdService.toIm("a")).thenReturn("im_a");
        doThrow(new ImRestException("IM_REST_ERROR", 2))
            .when(im).deleteGroupMembers(eq("g1"), any(), eq(false));

        assertThatThrownBy(() -> service.trySyncDeleteMembers("g1", List.of("a")))
            .isInstanceOf(ImRestException.class)
            .hasMessage("IM_REST_ERROR");
        verify(queuePublisher, never()).getIfAvailable();
    }

    @Test
    void trySyncDestroyGroup_failure_enqueues() {
        when(queuePublisher.getIfAvailable()).thenReturn(publisher);
        doThrow(new ImRestException("IM_REST_ERROR", 3)).when(im).destroyGroup("g1");

        service.trySyncDestroyGroup("g1");

        verify(publisher).enqueueGroupDestroy(eq("g1"), eq("IM_REST_ERROR:3"));
    }
}
