package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserBlockServiceTest {

    @Mock
    ImAdminClient im;
    @Mock
    UserRepository userRepository;

    @InjectMocks
    UserBlockService service;

    @Test
    void block_self_throwsInvalidInput() {
        assertThatThrownBy(() -> service.block("a", "a"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException e = (ResponseStatusException) ex;
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getReason()).isEqualTo("INVALID_INPUT");
            });
        verify(im, never()).addBlackList(any(), any());
    }

    @Test
    void block_missingUser_throwsNotFound() {
        when(userRepository.findByUserId("b")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.block("a", "b"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("USER_NOT_FOUND"));
        verify(im, never()).addBlackList(any(), any());
    }

    @Test
    void block_nonFriend_callsImAddOnly() {
        User target = new User();
        target.setUserId("b");
        when(userRepository.findByUserId("b")).thenReturn(Optional.of(target));

        service.block("a", "b");

        verify(im).addBlackList("a", "b");
        verify(im, never()).deleteFriendBoth(any(), any());
        verify(im, never()).deleteBlackList(any(), any());
    }

    @Test
    void unblock_callsImDelete() {
        service.unblock("a", "b");

        verify(im).deleteBlackList("a", "b");
        verify(im, never()).addBlackList(any(), any());
        verify(im, never()).deleteFriendBoth(any(), any());
    }

    @Test
    void isEitherBlocked_unconfigured_returnsFalse() {
        when(im.isConfigured()).thenReturn(false);

        assertThat(service.isEitherBlocked("a", "b")).isFalse();
        verify(im, never()).isEitherInBlackList(any(), any());
    }

    @Test
    void isEitherBlocked_configuredFailure_throwsImUnavailable() {
        when(im.isConfigured()).thenReturn(true);
        when(im.isEitherInBlackList("a", "b")).thenThrow(new ImRestException("IM_REST_UNAVAILABLE", 0));

        assertThatThrownBy(() -> service.isEitherBlocked("a", "b"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException e = (ResponseStatusException) ex;
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.getReason()).isEqualTo("IM_UNAVAILABLE");
            });
    }

    @Test
    void isEitherBlocked_configuredTrue() {
        when(im.isConfigured()).thenReturn(true);
        when(im.isEitherInBlackList("a", "b")).thenReturn(true);

        assertThat(service.isEitherBlocked("a", "b")).isTrue();
    }

    @Test
    void list_mapsProfileAndMillis() {
        when(im.listBlackList("a", 0, 50)).thenReturn(new ImAdminClient.BlackListPage(
            List.of(new ImAdminClient.BlackListEntry("b", 1_700_000_000L)), 0));
        User b = new User();
        b.setUserId("b");
        b.setNickname("Bob");
        b.setAvatarUrl("https://cdn/b.png");
        when(userRepository.findByUserIdIn(List.of("b"))).thenReturn(List.of(b));

        UserBlockService.BlockListResponse resp = service.list("a", 0, 50);

        assertThat(resp.hasMore()).isFalse();
        assertThat(resp.startIndex()).isEqualTo(0);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).userId()).isEqualTo("b");
        assertThat(resp.items().get(0).nickname()).isEqualTo("Bob");
        assertThat(resp.items().get(0).avatarUrl()).isEqualTo("https://cdn/b.png");
        assertThat(resp.items().get(0).blockedAt()).isEqualTo(1_700_000_000_000L);
    }
}
