package com.chat99.server.im.restqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImGroupRoleCache;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImRestJobHandlerFriendTest {

    @Mock
    ImAdminClient im;
    @Mock
    GroupProjectionService projection;
    @Mock
    ImGroupRoleCache roleCache;
    @Mock
    ImRestRateLimiter rateLimiter;
    @Mock
    ImRestCircuitBreaker circuitBreaker;
    @Mock
    ImUserIdService imUserIdService;
    @Mock
    com.chat99.server.group.GroupImSyncService groupImSyncService;
    @Mock
    com.chat99.server.group.GroupMembershipReconcileService membershipReconcile;

    ImRestJobHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ImRestJobHandler(
            im, projection, roleCache, rateLimiter, circuitBreaker, imUserIdService,
            groupImSyncService, membershipReconcile, new ObjectMapper());
        lenient().when(circuitBreaker.isOpen(any())).thenReturn(false);
        lenient().when(rateLimiter.acquire(any(), eq(2_000L))).thenReturn(true);
    }

    @Test
    void friendAddBoth_done() {
        ImRestJob job = friendJob(ImRestJob.Type.FRIEND_ADD_BOTH, "a", "b", null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).addFriendBoth("a", "b", "AddSource_Type_Server", null);
        verify(rateLimiter).acquire("sns-friend-add", 2_000L);
    }

    @Test
    void friendDeleteBoth_done() {
        ImRestJob job = friendJob(ImRestJob.Type.FRIEND_DELETE_BOTH, "a", "b", null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).deleteFriendBoth("a", "b");
        verify(rateLimiter).acquire("sns-friend-delete", 2_000L);
    }

    @Test
    void friendRemarkUpdate_done() {
        ImRestJob job = friendJob(ImRestJob.Type.FRIEND_REMARK_UPDATE, "a", "b", "");

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DONE);
        verify(im).updateFriendRemark("a", "b", "");
        verify(rateLimiter).acquire("sns-friend-update", 2_000L);
    }

    @Test
    void friendAddBoth_rateLimited_retriesWithoutCall() {
        when(rateLimiter.acquire("sns-friend-add", 2_000L)).thenReturn(false);
        ImRestJob job = friendJob(ImRestJob.Type.FRIEND_ADD_BOTH, "a", "b", null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.RETRY);
        verify(im, never()).addFriendBoth(any(), any(), any(), any());
    }

    @Test
    void friendAddBoth_blankPeer_dead() {
        ImRestJob job = friendJob(ImRestJob.Type.FRIEND_ADD_BOTH, "a", "  ", null);

        assertThat(handler.handle(job)).isEqualTo(ImRestJobHandler.Outcome.DEAD);
        verify(im, never()).addFriendBoth(any(), any(), any(), any());
    }

    private static ImRestJob friendJob(ImRestJob.Type type, String userId, String peer, String remark) {
        return new ImRestJob("j1", type, null, userId, null, 0, 1L, "test", peer, remark, null, null);
    }
}
