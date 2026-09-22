package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserPrivacyServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    UserFriendRepository friendRepository;
    @Mock
    UserBlockService blockService;

    @InjectMocks
    UserPrivacyService service;

    @Test
    void getPrivacy_returnsAllowViaCardFalse() {
        User u = user("b1", false);
        when(userRepository.findByUserId("b1")).thenReturn(Optional.of(u));

        UserPrivacyService.PrivacyView view = service.getPrivacyForUser("b1");

        assertThat(view.allowViaCard()).isFalse();
        assertThat(view.allowViaQrCode()).isTrue();
    }

    @Test
    void checkAddFriend_cardAllowed_includesRequiresVerify() {
        User u = user("b1", true);
        u.setFriendAddRequiresVerify(false);
        when(userRepository.findByUserId("b1")).thenReturn(Optional.of(u));

        UserPrivacyService.AddFriendCheckResult r = service.checkAddFriend("b1", "card");

        assertThat(r.allowed()).isTrue();
        assertThat(r.reason()).isNull();
        assertThat(r.friendAddRequiresVerify()).isFalse();
    }

    @Test
    void checkAddFriend_cardDisabled() {
        User u = user("b1", false);
        when(userRepository.findByUserId("b1")).thenReturn(Optional.of(u));

        UserPrivacyService.AddFriendCheckResult r = service.checkAddFriend("b1", "card");

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo("ADD_FRIEND_VIA_CARD_DISABLED");
    }

    @Test
    void checkAddFriend_cardAllowed() {
        User u = user("b1", true);
        when(userRepository.findByUserId("b1")).thenReturn(Optional.of(u));

        UserPrivacyService.AddFriendCheckResult r = service.checkAddFriend("b1", "card");

        assertThat(r.allowed()).isTrue();
        assertThat(r.reason()).isNull();
    }

    @Test
    void getPrivacy_userNotFound() {
        when(userRepository.findByUserId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPrivacyForUser("missing"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                .isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void visibleLastActiveAt_hidden() {
        User u = user("b1", true);
        u.setLastActiveAt(java.time.Instant.ofEpochMilli(1700000000000L));
        u.setLastActiveVisibility(LastActiveVisibility.hidden);

        assertThat(service.visibleLastActiveAt(u, "a1")).isNull();
    }

    @Test
    void visibleLastActiveAt_everyone() {
        User u = user("b1", true);
        u.setLastActiveAt(java.time.Instant.ofEpochMilli(1700000000000L));

        assertThat(service.visibleLastActiveAt(u, "a1")).isEqualTo(1700000000000L);
    }

    @Test
    void visibleLastActiveAt_friendsOnly_mutual() {
        User u = user("b1", true);
        u.setLastActiveAt(java.time.Instant.ofEpochMilli(1700000000000L));
        u.setLastActiveVisibility(LastActiveVisibility.friends_only);
        when(friendRepository.isMutualActive("a1", "b1")).thenReturn(true);

        assertThat(service.visibleLastActiveAt(u, "a1")).isEqualTo(1700000000000L);
    }

    @Test
    void visibleLastActiveAt_friendsOnly_notMutual() {
        User u = user("b1", true);
        u.setLastActiveAt(java.time.Instant.ofEpochMilli(1700000000000L));
        u.setLastActiveVisibility(LastActiveVisibility.friends_only);
        when(friendRepository.isMutualActive("a1", "b1")).thenReturn(false);

        assertThat(service.visibleLastActiveAt(u, "a1")).isNull();
    }

    @Test
    void lastActiveAtEpochMillis_returnsRawTimestampRegardlessOfVisibility() {
        User u = user("b1", true);
        u.setLastActiveAt(java.time.Instant.ofEpochMilli(1700000000000L));
        u.setLastActiveVisibility(LastActiveVisibility.hidden);

        assertThat(service.lastActiveAtEpochMillis(u)).isEqualTo(1700000000000L);
    }

    @Test
    void lastActiveAtEpochMillis_nullWhenNoActivity() {
        User u = user("b1", true);
        assertThat(service.lastActiveAtEpochMillis(u)).isNull();
    }

    @Test
    void checkAddFriendForCaller_blocked_returnsUserBlocked() {
        User u = user("b1", true);
        when(userRepository.findByUserId("b1")).thenReturn(Optional.of(u));
        when(blockService.isEitherBlocked("a1", "b1")).thenReturn(true);

        UserPrivacyService.AddFriendCheckResult r = service.checkAddFriendForCaller("a1", "b1", "card");

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo("USER_BLOCKED");
        assertThat(r.friendAddRequiresVerify()).isTrue();
    }

    @Test
    void checkAddFriendForCaller_unblocked_usesChannel() {
        User u = user("b1", false);
        when(userRepository.findByUserId("b1")).thenReturn(Optional.of(u));
        when(blockService.isEitherBlocked("a1", "b1")).thenReturn(false);

        UserPrivacyService.AddFriendCheckResult r = service.checkAddFriendForCaller("a1", "b1", "card");

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo("ADD_FRIEND_VIA_CARD_DISABLED");
    }

    private static User user(String userId, boolean allowViaCard) {
        User u = new User();
        u.setUserId(userId);
        u.setPhone("+8613800000001");
        u.setPasswordHash("x");
        u.setNickname("test");
        u.setAvatarUrl("https://example.com/a.png");
        u.setAllowViaCard(allowViaCard);
        u.setStatus(1);
        return u;
    }
}
