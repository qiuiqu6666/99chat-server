package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.chat99.server.common.PhoneUtils;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    UserPrivacyService privacyService;
    @Mock
    PhoneUtils phoneUtils;

    @InjectMocks
    UserProfileService service;

    @Test
    void getProfile_returnsPublicFields() {
        User u = new User();
        u.setUserId("ab12cd34ef");
        u.setNickname("张三");
        u.setAvatarUrl("https://cdn.example/a.png");
        u.setPhone("+8613812345678");
        u.setStatus(1);
        u.setLastActiveAt(Instant.ofEpochMilli(1_700_000_000_000L));
        u.setLastActiveVisibility(LastActiveVisibility.friends_only);

        when(privacyService.requireActiveUser("ab12cd34ef")).thenReturn(u);
        when(privacyService.visibleLastActiveAt(u, "viewer1")).thenReturn(1_700_000_000_000L);
        when(privacyService.lastActiveVisibilityOf(u)).thenReturn(LastActiveVisibility.friends_only);
        when(phoneUtils.mask("+8613812345678")).thenReturn("+86138****5678");

        UserProfileService.UserProfileView view = service.getProfile("viewer1", "ab12cd34ef");

        assertThat(view.userId()).isEqualTo("ab12cd34ef");
        assertThat(view.nickname()).isEqualTo("张三");
        assertThat(view.avatarUrl()).isEqualTo("https://cdn.example/a.png");
        assertThat(view.phoneMasked()).isEqualTo("+86138****5678");
        assertThat(view.lastActiveAt()).isEqualTo(1_700_000_000_000L);
        assertThat(view.lastActiveVisibility()).isEqualTo(LastActiveVisibility.friends_only);
    }

    @Test
    void getProfile_hiddenLastActive_returnsNullTimestamp() {
        User u = new User();
        u.setUserId("ab12cd34ef");
        u.setNickname("张三");
        u.setPhone("+8613812345678");
        u.setStatus(1);
        when(privacyService.requireActiveUser("ab12cd34ef")).thenReturn(u);
        when(privacyService.visibleLastActiveAt(u, "viewer1")).thenReturn(null);
        when(privacyService.lastActiveVisibilityOf(u)).thenReturn(LastActiveVisibility.hidden);
        when(phoneUtils.mask("+8613812345678")).thenReturn("+86138****5678");

        UserProfileService.UserProfileView view = service.getProfile("viewer1", "ab12cd34ef");

        assertThat(view.lastActiveAt()).isNull();
        assertThat(view.lastActiveVisibility()).isEqualTo(LastActiveVisibility.hidden);
    }

    @Test
    void getProfile_notFound_propagates() {
        when(privacyService.requireActiveUser("missing"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        assertThatThrownBy(() -> service.getProfile("viewer1", "missing"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                .isEqualTo("USER_NOT_FOUND"));
    }
}
