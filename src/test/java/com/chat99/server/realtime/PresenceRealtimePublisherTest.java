package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.LastActiveVisibility;
import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserPrivacyService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PresenceRealtimePublisherTest {

    @Mock
    ApplicationEventPublisher events;
    @Mock
    UserRepository userRepository;
    @Mock
    UserFriendRepository friendRepository;
    @Mock
    UserPrivacyService privacyService;

    @InjectMocks
    PresenceRealtimePublisher publisher;

    @Test
    void userBecameActive_notifiesAllFriendListOwnersWithRawData() {
        User active = user("b", LastActiveVisibility.friends_only);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(active));
        when(privacyService.lastActiveVisibilityOf(active)).thenReturn(LastActiveVisibility.friends_only);
        when(friendRepository.findOwnerUserIdsByFriendUserIdAndStatus("b", UserFriend.STATUS_ACTIVE))
            .thenReturn(List.of("a", "c"));

        Instant ts = Instant.parse("2026-06-17T03:00:00Z");
        publisher.userBecameActive("b", ts);

        ArgumentCaptor<PresenceRealtimePublisher.PresenceChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(PresenceRealtimePublisher.PresenceChangedCommittedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(PresenceRealtimePublisher.PresenceChangedCommittedEvent::targetUserId)
            .containsExactlyInAnyOrder("a", "c");
        assertThat(captor.getAllValues()).allMatch(e ->
            e.peerUserId().equals("b")
                && e.lastActiveAt().equals(ts.toEpochMilli())
                && e.lastActiveVisibility() == LastActiveVisibility.friends_only
                && e.online());
    }

    @Test
    void userBecameActive_notifiesViewersWhenHidden() {
        User active = user("b", LastActiveVisibility.hidden);
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(active));
        when(privacyService.lastActiveVisibilityOf(active)).thenReturn(LastActiveVisibility.hidden);
        when(friendRepository.findOwnerUserIdsByFriendUserIdAndStatus("b", UserFriend.STATUS_ACTIVE))
            .thenReturn(List.of("a"));

        Instant ts = Instant.parse("2026-06-17T03:00:00Z");
        publisher.userBecameActive("b", ts);

        ArgumentCaptor<PresenceRealtimePublisher.PresenceChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(PresenceRealtimePublisher.PresenceChangedCommittedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().lastActiveAt()).isEqualTo(ts.toEpochMilli());
        assertThat(captor.getValue().lastActiveVisibility()).isEqualTo(LastActiveVisibility.hidden);
    }

    @Test
    void lastActiveVisibilityChanged_notifiesAllFriendListOwnersWithRawData() {
        User user = user("b", LastActiveVisibility.hidden);
        user.setLastActiveAt(Instant.parse("2026-06-17T20:00:00Z"));
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(user));
        when(friendRepository.findOwnerUserIdsByFriendUserIdAndStatus("b", UserFriend.STATUS_ACTIVE))
            .thenReturn(List.of("a", "c"));

        publisher.lastActiveVisibilityChanged("b", LastActiveVisibility.everyone, LastActiveVisibility.hidden);

        ArgumentCaptor<PresenceRealtimePublisher.PresenceChangedCommittedEvent> captor =
            ArgumentCaptor.forClass(PresenceRealtimePublisher.PresenceChangedCommittedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).allMatch(e ->
            e.peerUserId().equals("b")
                && e.lastActiveAt().equals(user.getLastActiveAt().toEpochMilli())
                && e.lastActiveVisibility() == LastActiveVisibility.hidden
                && !e.online());
    }

    private static User user(String userId, LastActiveVisibility visibility) {
        User u = new User();
        u.setUserId(userId);
        u.setStatus(1);
        u.setLastActiveVisibility(visibility);
        return u;
    }
}
