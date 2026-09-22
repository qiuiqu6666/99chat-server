package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.FriendContactChangeWriter;
import com.chat99.server.user.LastActiveVisibility;
import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserPrivacyService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class FriendListRealtimePublisherTest {

    @Mock
    ApplicationEventPublisher events;
    @Mock
    UserFriendRepository friendRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    UserPrivacyService privacyService;
    @Mock
    FriendContactChangeWriter changeWriter;

    @InjectMocks
    FriendListRealtimePublisher publisher;

    @BeforeEach
    void stubSeq() {
        when(changeWriter.write(anyString(), anyString(), anyString(), anyMap(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(new FriendContactChangeWriter.WriteResult(42L, 42L, "evt1"));
    }

    @Test
    void mutualAdded_publishesSnapshotForBothUsers() {
        UserFriend ab = activeEdge("a", "b", "Bob");
        UserFriend ba = activeEdge("b", "a", "Alice");
        User b = user("b", "Bob");
        User a = user("a", "Alice");
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(ab));
        when(friendRepository.findByUserIdAndFriendUserId("b", "a")).thenReturn(Optional.of(ba));
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("b", "a", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("a", "b", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));
        when(userRepository.findByUserId("a")).thenReturn(Optional.of(a));
        when(privacyService.lastActiveAtEpochMillis(b)).thenReturn(1000L);
        when(privacyService.lastActiveAtEpochMillis(a)).thenReturn(2000L);
        when(privacyService.lastActiveVisibilityOf(b)).thenReturn(LastActiveVisibility.everyone);
        when(privacyService.lastActiveVisibilityOf(a)).thenReturn(LastActiveVisibility.everyone);

        publisher.mutualAdded("a", "b");

        ArgumentCaptor<FriendListRealtimePublisher.FriendListChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(FriendListRealtimePublisher.FriendListChangedCommittedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(FriendListRealtimePublisher.FriendListChangedCommittedEvent::targetUserId)
            .containsExactlyInAnyOrder("a", "b");
        FriendListRealtimePublisher.FriendListChangedCommittedEvent forA = captor.getAllValues().stream()
            .filter(e -> "a".equals(e.targetUserId()))
            .findFirst()
            .orElseThrow();
        assertThat(forA.action()).isEqualTo(FriendListRealtimePublisher.ACTION_ADDED);
        assertThat(forA.peerUserId()).isEqualTo("b");
        assertThat(forA.isFriend()).isTrue();
        assertThat(forA.canMessage()).isTrue();
        assertThat(forA.lastActiveAt()).isEqualTo(1000L);
        assertThat(forA.lastActiveVisibility()).isEqualTo(LastActiveVisibility.everyone);
        assertThat(forA.seq()).isEqualTo(42L);
        verify(changeWriter).write(eq("a"), eq(FriendContactChangeWriter.TYPE_CONTACT_CREATED), eq("b"), any(), eq(false));
        verify(changeWriter).write(eq("b"), eq(FriendContactChangeWriter.TYPE_CONTACT_CREATED), eq("a"), any(), eq(false));
    }

    @Test
    void peerProfileUpdated_notifiesAllFriendsKeepingPeer() {
        UserFriend row = activeEdge("a", "b", "Bob");
        User b = user("b", "BobNew");
        when(friendRepository.findOwnerUserIdsByFriendUserIdAndStatus("b", UserFriend.STATUS_ACTIVE))
            .thenReturn(java.util.List.of("a"));
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(row));
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("b", "a", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));
        when(privacyService.lastActiveAtEpochMillis(b)).thenReturn(500L);
        when(privacyService.lastActiveVisibilityOf(b)).thenReturn(LastActiveVisibility.hidden);

        publisher.peerProfileUpdated("b");

        ArgumentCaptor<FriendListRealtimePublisher.FriendListChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(FriendListRealtimePublisher.FriendListChangedCommittedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().targetUserId()).isEqualTo("a");
        assertThat(captor.getValue().action()).isEqualTo(FriendListRealtimePublisher.ACTION_PROFILE_UPDATED);
        assertThat(captor.getValue().peerUserId()).isEqualTo("b");
        assertThat(captor.getValue().lastActiveAt()).isEqualTo(500L);
        assertThat(captor.getValue().lastActiveVisibility()).isEqualTo(LastActiveVisibility.hidden);
        assertThat(captor.getValue().seq()).isEqualTo(42L);
    }

    @Test
    void remarkUpdated_publishesSnapshotForOwner() {
        UserFriend ab = activeEdge("a", "b", "Bob");
        ab.setRemark("备注小明");
        User b = user("b", "Bob");
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(ab));
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("b", "a", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));
        when(privacyService.lastActiveAtEpochMillis(b)).thenReturn(null);
        when(privacyService.lastActiveVisibilityOf(b)).thenReturn(LastActiveVisibility.everyone);

        publisher.remarkUpdated("a", "b");

        ArgumentCaptor<FriendListRealtimePublisher.FriendListChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(FriendListRealtimePublisher.FriendListChangedCommittedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().targetUserId()).isEqualTo("a");
        assertThat(captor.getValue().action()).isEqualTo(FriendListRealtimePublisher.ACTION_REMARK_UPDATED);
        assertThat(captor.getValue().peerUserId()).isEqualTo("b");
        assertThat(captor.getValue().remark()).isEqualTo("备注小明");
        verify(changeWriter).write(
            eq("a"), eq(FriendContactChangeWriter.TYPE_CONTACT_REMARK_UPDATED), eq("b"), any(), eq(false));
    }

    @Test
    void oneWayDeleted_notifiesPeerWhenReverseEdgeRemains_legacy() {
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("b", "a", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        UserFriend ba = activeEdge("b", "a", "Alice");
        User a = user("a", "Alice");
        when(friendRepository.findByUserIdAndFriendUserId("b", "a")).thenReturn(Optional.of(ba));
        when(userRepository.findByUserId("a")).thenReturn(Optional.of(a));
        when(privacyService.lastActiveAtEpochMillis(a)).thenReturn(null);
        when(privacyService.lastActiveVisibilityOf(a)).thenReturn(LastActiveVisibility.everyone);

        publisher.oneWayDeleted("a", "b");

        ArgumentCaptor<FriendListRealtimePublisher.FriendListChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(FriendListRealtimePublisher.FriendListChangedCommittedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(FriendListRealtimePublisher.FriendListChangedCommittedEvent::action)
            .containsExactlyInAnyOrder(
                FriendListRealtimePublisher.ACTION_REMOVED,
                FriendListRealtimePublisher.ACTION_UPDATED);
        verify(changeWriter).write(
            eq("a"), eq(FriendContactChangeWriter.TYPE_CONTACT_DELETED), eq("b"), any(), eq(true));
    }

    @Test
    void mutualDeleted_publishesRemovedForBothSides() {
        publisher.mutualDeleted("a", "b");

        ArgumentCaptor<FriendListRealtimePublisher.FriendListChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(FriendListRealtimePublisher.FriendListChangedCommittedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(FriendListRealtimePublisher.FriendListChangedCommittedEvent::action)
            .containsOnly(FriendListRealtimePublisher.ACTION_REMOVED);
        assertThat(captor.getAllValues())
            .extracting(FriendListRealtimePublisher.FriendListChangedCommittedEvent::targetUserId)
            .containsExactlyInAnyOrder("a", "b");
        verify(changeWriter).write(
            eq("a"), eq(FriendContactChangeWriter.TYPE_CONTACT_DELETED), eq("b"), any(), eq(true));
        verify(changeWriter).write(
            eq("b"), eq(FriendContactChangeWriter.TYPE_CONTACT_DELETED), eq("a"), any(), eq(true));
    }

    @Test
    void lastActiveVisibilityChanged_notifiesAllFriendListOwnersWithRawData() {
        when(friendRepository.findOwnerUserIdsByFriendUserIdAndStatus("b", UserFriend.STATUS_ACTIVE))
            .thenReturn(java.util.List.of("a", "c"));
        UserFriend ab = activeEdge("a", "b", "Bob");
        UserFriend cb = activeEdge("c", "b", "Bob");
        User b = user("b", "Bob");
        b.setLastActiveAt(Instant.parse("2026-06-17T20:00:00Z"));
        when(friendRepository.findByUserIdAndFriendUserId("a", "b")).thenReturn(Optional.of(ab));
        when(friendRepository.findByUserIdAndFriendUserId("c", "b")).thenReturn(Optional.of(cb));
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("b", "a", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        when(friendRepository.existsByUserIdAndFriendUserIdAndStatus("b", "c", UserFriend.STATUS_ACTIVE))
            .thenReturn(true);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(b));
        when(privacyService.lastActiveAtEpochMillis(b)).thenReturn(b.getLastActiveAt().toEpochMilli());
        when(privacyService.lastActiveVisibilityOf(b)).thenReturn(LastActiveVisibility.hidden);

        publisher.lastActiveVisibilityChanged("b");

        ArgumentCaptor<FriendListRealtimePublisher.FriendListChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(FriendListRealtimePublisher.FriendListChangedCommittedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
            .allMatch(e -> FriendListRealtimePublisher.ACTION_PROFILE_UPDATED.equals(e.action()));
        assertThat(captor.getAllValues())
            .allMatch(e -> e.lastActiveAt().equals(b.getLastActiveAt().toEpochMilli()));
        assertThat(captor.getAllValues())
            .allMatch(e -> e.lastActiveVisibility() == LastActiveVisibility.hidden);
    }

    private static UserFriend activeEdge(String userId, String friendUserId, String nickname) {
        UserFriend row = new UserFriend();
        row.setUserId(userId);
        row.setFriendUserId(friendUserId);
        row.setFriendNickname(nickname);
        row.setStatus(UserFriend.STATUS_ACTIVE);
        return row;
    }

    private static User user(String userId, String nickname) {
        User u = new User();
        u.setUserId(userId);
        u.setNickname(nickname);
        u.setStatus(1);
        return u;
    }
}
