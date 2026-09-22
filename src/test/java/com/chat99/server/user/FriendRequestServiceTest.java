package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.FriendRequestRealtimePublisher;
import com.chat99.server.user.FriendApplicationHistory.AddSource;
import com.chat99.server.user.FriendRequest.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FriendRequestServiceTest {

    @Mock
    FriendRequestRepository requestRepository;
    @Mock
    FriendApplicationRepository historyRepository;
    @Mock
    UserFriendService friendService;
    @Mock
    UserPrivacyService privacyService;
    @Mock
    FriendRequestRateLimiter rateLimiter;
    @Mock
    UserRepository userRepository;
    @Mock
    FriendRequestRealtimePublisher realtimePublisher;
    @Mock
    UserBlockService blockService;

    @InjectMocks
    FriendRequestService service;

    @Test
    void listIncoming_loadsPeerProfileFromUsersTable() {
        FriendRequest req = pending("from1", "me");
        when(requestRepository.findByToUserIdAndStatusOrderByCreatedAtDesc(
            eq("me"), eq(Status.pending), any(Pageable.class))).thenReturn(List.of(req));

        User from = user("from1", "Alice", "https://cdn.example.com/a-new.png");
        when(userRepository.findByUserIdIn(List.of("from1"))).thenReturn(List.of(from));

        FriendRequestService.RequestListResponse resp = service.listIncoming("me", 100);

        assertThat(resp.items()).hasSize(1);
        FriendRequestService.RequestItem item = resp.items().get(0);
        assertThat(item.peerUserId()).isEqualTo("from1");
        assertThat(item.peerNickname()).isEqualTo("Alice");
        assertThat(item.peerAvatarUrl()).isEqualTo("https://cdn.example.com/a-new.png");
    }

    @Test
    void listOutgoing_loadsPeerProfileFromUsersTable() {
        FriendRequest req = pending("me", "to1");
        when(requestRepository.findByFromUserIdAndStatusOrderByCreatedAtDesc(
            eq("me"), eq(Status.pending), any(Pageable.class))).thenReturn(List.of(req));

        User to = user("to1", "Bob", "https://cdn.example.com/b.png");
        when(userRepository.findByUserIdIn(List.of("to1"))).thenReturn(List.of(to));

        FriendRequestService.RequestItem item = service.listOutgoing("me", 100).items().get(0);

        assertThat(item.peerUserId()).isEqualTo("to1");
        assertThat(item.peerNickname()).isEqualTo("Bob");
        assertThat(item.peerAvatarUrl()).isEqualTo("https://cdn.example.com/b.png");
    }

    private static FriendRequest pending(String from, String to) {
        FriendRequest req = new FriendRequest();
        req.setId(1L);
        req.setFromUserId(from);
        req.setToUserId(to);
        req.setAddSource(FriendApplicationHistory.AddSource.search);
        req.setStatus(Status.pending);
        req.setCreatedAt(Instant.parse("2026-06-15T00:00:00Z"));
        return req;
    }

    private static FriendRequest sent(String from, String to, Status status) {
        FriendRequest req = pending(from, to);
        req.setStatus(status);
        if (status != Status.pending) {
            req.setHandledAt(Instant.parse("2026-06-15T01:00:00Z"));
        }
        return req;
    }

    @Test
    void listSent_includesAcceptedAndRejected() {
        when(requestRepository.findByFromUserIdOrderByCreatedAtDesc(
            eq("me"), any(Pageable.class))).thenReturn(List.of(
            sent("me", "p1", Status.pending),
            sent("me", "p2", Status.accepted),
            sent("me", "p3", Status.rejected)));

        User u1 = user("p1", "U1", "https://cdn/a1.png");
        User u2 = user("p2", "U2", "https://cdn/a2.png");
        User u3 = user("p3", "U3", "https://cdn/a3.png");
        when(userRepository.findByUserIdIn(any())).thenReturn(List.of(u1, u2, u3));

        FriendRequestService.RequestListResponse resp = service.listSent("me", 100);

        assertThat(resp.items()).hasSize(3);
        assertThat(resp.items()).extracting(FriendRequestService.RequestItem::status)
            .containsExactly("pending", "accepted", "rejected");
        assertThat(resp.items().get(1).peerNickname()).isEqualTo("U2");
        assertThat(resp.items().get(2).handledAt()).isNotNull();
    }

    @Test
    void deleteIncoming_clearsCooldownForPending() {
        FriendRequest req = pending("from1", "me");
        when(requestRepository.findByIdAndToUserId(1L, "me")).thenReturn(java.util.Optional.of(req));

        FriendRequestService.DeleteResponse resp = service.deleteIncoming("me", 1L);

        assertThat(resp.deleted()).isTrue();
        verify(rateLimiter).clear("from1", "me");
        verify(requestRepository).delete(req);
    }

    @Test
    void deleteSentBatch_deletesOwnedRows() {
        FriendRequest r1 = sent("me", "p1", Status.accepted);
        r1.setId(1L);
        FriendRequest r2 = sent("me", "p2", Status.rejected);
        r2.setId(2L);
        when(requestRepository.findByIdInAndFromUserId(List.of(1L, 2L), "me")).thenReturn(List.of(r1, r2));

        FriendRequestService.BatchDeleteResponse resp = service.deleteSentBatch("me", List.of(1L, 2L));

        assertThat(resp.deleted()).isEqualTo(2);
        verify(requestRepository).delete(r1);
        verify(requestRepository).delete(r2);
    }

    @Test
    void createRequest_afterOneSidedDelete_goesPendingWhenVerifyRequired_notRestored() {
        when(friendService.isMutualActive("a", "b")).thenReturn(false);
        when(privacyService.checkAddFriendBySource("b", "search"))
            .thenReturn(new UserPrivacyService.AddFriendCheckResult(true, null, true));
        when(requestRepository.findByFromUserIdAndToUserIdAndStatus("b", "a", Status.pending))
            .thenReturn(Optional.empty());
        when(requestRepository.findByFromUserIdAndToUserIdAndStatus("a", "b", Status.pending))
            .thenReturn(Optional.empty());
        User target = user("b", "Bob", null);
        target.setFriendAddRequiresVerify(true);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(target));
        when(requestRepository.save(any(FriendRequest.class))).thenAnswer(inv -> {
            FriendRequest r = inv.getArgument(0);
            r.setId(99L);
            return r;
        });

        FriendRequestService.CreateRequestResult result =
            service.createRequest("a", "b", "hi", "search");

        assertThat(result.outcome()).isEqualTo("pending");
        assertThat(result.requestId()).isEqualTo(99L);
        verify(friendService, never()).reviveActiveEdge(any(), any());
        verify(friendService, never()).bindMutualFriends(any(), any());
        verify(realtimePublisher, never()).restored(any(), any(), any(), any(), any());
        verify(realtimePublisher).pendingCreated(any(FriendRequest.class));
        ArgumentCaptor<FriendRequest> captor = ArgumentCaptor.forClass(FriendRequest.class);
        verify(requestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Status.pending);
        verify(rateLimiter).checkAndMark("a", "b");
    }

    @Test
    void createRequest_blocked_throwsUserBlocked() {
        when(blockService.isEitherBlocked("a", "b")).thenReturn(true);

        assertThatThrownBy(() -> service.createRequest("a", "b", "hi", "search"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException e = (ResponseStatusException) ex;
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(e.getReason()).isEqualTo("USER_BLOCKED");
            });
        verify(privacyService, never()).checkAddFriendBySource(any(), any());
        verify(friendService, never()).bindMutualFriends(any(), any());
    }

    @Test
    void createRequest_imCheckFailed_throwsImUnavailable() {
        when(blockService.isEitherBlocked("a", "b"))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IM_UNAVAILABLE"));

        assertThatThrownBy(() -> service.createRequest("a", "b", "hi", "search"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException e = (ResponseStatusException) ex;
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.getReason()).isEqualTo("IM_UNAVAILABLE");
            });
        verify(privacyService, never()).checkAddFriendBySource(any(), any());
    }

    @Test
    void acceptRequest_blocked_throwsUserBlocked() {
        FriendRequest req = pending("a", "me");
        when(requestRepository.findByIdAndToUserId(1L, "me")).thenReturn(Optional.of(req));
        when(blockService.isEitherBlocked("a", "me")).thenReturn(true);

        assertThatThrownBy(() -> service.acceptRequest("me", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException e = (ResponseStatusException) ex;
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(e.getReason()).isEqualTo("USER_BLOCKED");
            });
        verify(friendService, never()).bindMutualFriends(any(), any());
    }

    @Test
    void rejectPendingBetween_rejectsBothDirectionsAsReceiver() {
        FriendRequest aToB = pending("a", "b");
        aToB.setId(11L);
        FriendRequest bToA = pending("b", "a");
        bToA.setId(22L);
        when(requestRepository.findByFromUserIdAndToUserIdAndStatus("a", "b", Status.pending))
            .thenReturn(Optional.of(aToB));
        when(requestRepository.findByFromUserIdAndToUserIdAndStatus("b", "a", Status.pending))
            .thenReturn(Optional.of(bToA));
        when(userRepository.findByUserId("a")).thenReturn(Optional.of(user("a", "A", null)));
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(user("b", "B", null)));
        when(historyRepository.findByUserIdAndPeerUserIdAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(requestRepository.save(any(FriendRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        service.rejectPendingBetween("a", "b");

        assertThat(aToB.getStatus()).isEqualTo(Status.rejected);
        assertThat(bToA.getStatus()).isEqualTo(Status.rejected);
        verify(requestRepository).save(aToB);
        verify(requestRepository).save(bToA);
        verify(rateLimiter).clear("a", "b");
        verify(rateLimiter).clear("b", "a");
        verify(realtimePublisher).rejected(aToB);
        verify(realtimePublisher).rejected(bToA);
    }

    private static User user(String userId, String nickname, String avatarUrl) {
        User u = new User();
        u.setUserId(userId);
        u.setNickname(nickname);
        u.setAvatarUrl(avatarUrl);
        u.setStatus(1);
        return u;
    }
}
