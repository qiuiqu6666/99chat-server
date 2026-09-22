package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.sync.SyncProperties;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContactMatchServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserFriendRepository friendRepository;
    @Mock UserPrivacyService privacyService;
    @Mock ContactMatchRateLimiter rateLimiter;

    ContactMatchService service;

    @BeforeEach
    void setUp() {
        service = new ContactMatchService(
            userRepository,
            friendRepository,
            new PhoneUtils(),
            privacyService,
            rateLimiter,
            new SyncProperties("user-backup/", 60, 500, 100, 3600, 104857600L));
    }

    @Test
    void match_returnsRegisteredAndUnregistered() {
        User registered = activeUser("user_abc", "+8613800138000", "张三");
        when(userRepository.findByPhoneIn(Set.of("+8613800138000", "+8613900139000")))
            .thenReturn(List.of(registered));
        when(friendRepository.findMutualFriendUserIdsAmong(eq("self"), eq(List.of("user_abc"))))
            .thenReturn(List.of("user_abc"));
        when(privacyService.lastActiveAtEpochMillis(registered)).thenReturn(1_710_000_000_000L);
        when(privacyService.lastActiveVisibilityOf(registered)).thenReturn(LastActiveVisibility.everyone);

        ContactMatchService.MatchResponse response = service.match("self",
            new ContactMatchService.MatchCommand(
                List.of("+8613800138000", "13900139000"),
                "CN",
                true));

        assertThat(response.items()).hasSize(2);
        ContactMatchService.ContactMatchItem hit = response.items().get(0);
        assertThat(hit.phone()).isEqualTo("+8613800138000");
        assertThat(hit.registered()).isTrue();
        assertThat(hit.userId()).isEqualTo("user_abc");
        assertThat(hit.nickname()).isEqualTo("张三");
        assertThat(hit.isFriend()).isTrue();
        assertThat(hit.lastActiveAt()).isEqualTo(1_710_000_000_000L);

        ContactMatchService.ContactMatchItem miss = response.items().get(1);
        assertThat(miss.phone()).isEqualTo("+8613900139000");
        assertThat(miss.registered()).isFalse();
        assertThat(miss.userId()).isNull();
        verify(rateLimiter).check("self");
    }

    @Test
    void match_doesNotUseSearchRateLimiter() {
        when(userRepository.findByPhoneIn(any())).thenReturn(List.of());

        service.match("self", new ContactMatchService.MatchCommand(List.of("13800138000"), "CN", false));

        verify(rateLimiter).check("self");
    }

    @Test
    void match_rejectsTooManyPhones() {
        List<String> phones = java.util.stream.IntStream.range(0, 501)
            .mapToObj(i -> "1380013" + String.format("%04d", i))
            .toList();

        assertThatThrownBy(() -> service.match("self",
            new ContactMatchService.MatchCommand(phones, "CN", true)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("TOO_MANY_PHONES");
    }

    @Test
    void match_rejectsInvalidPhone() {
        assertThatThrownBy(() -> service.match("self",
            new ContactMatchService.MatchCommand(List.of("not-a-phone"), "CN", true)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("INVALID_INPUT");
    }

    private static User activeUser(String userId, String phone, String nickname) {
        User user = new User();
        user.setUserId(userId);
        user.setPhone(phone);
        user.setNickname(nickname);
        user.setStatus(1);
        user.setLastActiveAt(Instant.ofEpochMilli(1_710_000_000_000L));
        return user;
    }
}
