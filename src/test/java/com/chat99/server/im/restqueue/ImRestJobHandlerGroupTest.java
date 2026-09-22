package com.chat99.server.im.restqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImGroupRoleCache;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImRestJobHandlerGroupTest {

    @Mock ImAdminClient im;
    @Mock GroupProjectionService projection;
    @Mock ImGroupRoleCache roleCache;
    @Mock ImRestRateLimiter rateLimiter;
    @Mock ImRestCircuitBreaker circuitBreaker;
    @Mock ImUserIdService imUserIdService;
    @Mock com.chat99.server.group.GroupImSyncService groupImSyncService;

    ImRestJobHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ImRestJobHandler(
            im, projection, roleCache, rateLimiter, circuitBreaker, imUserIdService,
            groupImSyncService, new ObjectMapper());
        lenient().when(circuitBreaker.isOpen(any())).thenReturn(false);
        lenient().when(rateLimiter.acquire(any(), eq(2_000L))).thenReturn(true);
    }

    @Test
    void groupModifyFaceUrl_done() {
        ImRestJob job = new ImRestJob(
            "j1", ImRestJob.Type.GROUP_MODIFY_FACE_URL, "g1", null, null, 0, 1L, "t",
            null, null, null, "https://x/a.png");

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).modifyGroupFaceUrl("g1", "https://x/a.png");
    }

    @Test
    void groupAddMembers_mapsToIm() {
        when(imUserIdService.toIm("a")).thenReturn("im_a");
        ImRestJob job = new ImRestJob(
            "j1", ImRestJob.Type.GROUP_ADD_MEMBERS, "g1", null, null, 0, 1L, "t",
            null, null, List.of("a"), null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).addGroupMembers("g1", List.of("im_a"), false);
        verify(groupImSyncService).enqueueMembershipReconcile("g1", List.of("a"), "im_job_add");
    }

    @Test
    void refreshRole_notMember_removesLocal() {
        when(imUserIdService.toImAccount("u1")).thenReturn("u1");
        when(im.getRoleInGroupResult("g1", "u1"))
            .thenReturn(new com.chat99.server.im.ImAdminClient.ImRoleFetchResult(
                "NotMember",
                com.chat99.server.im.ImAdminClient.ImRoleFetchResult.Status.OK,
                0));
        ImRestJob job = new ImRestJob(
            "j1", ImRestJob.Type.REFRESH_ROLE, "g1", "u1", null, 0, 1L, "t",
            null, null, null, null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(projection).onMembersRemoved("g1", List.of("u1"));
        verify(roleCache).evict("g1", "u1");
    }

    @Test
    void groupDeleteMembers_reconciles() {
        when(imUserIdService.toIm("a")).thenReturn("im_a");
        ImRestJob job = new ImRestJob(
            "j1", ImRestJob.Type.GROUP_DELETE_MEMBERS, "g1", null, null, 0, 1L, "t",
            null, null, List.of("a"), null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).deleteGroupMembers("g1", List.of("im_a"), false);
        verify(groupImSyncService).enqueueMembershipReconcile("g1", List.of("a"), "im_job_delete");
    }

    @Test
    void groupDestroy_done() {
        ImRestJob job = new ImRestJob(
            "j1", ImRestJob.Type.GROUP_DESTROY, "g1", null, null, 0, 1L, "t",
            null, null, null, null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).destroyGroup("g1");
    }
}
