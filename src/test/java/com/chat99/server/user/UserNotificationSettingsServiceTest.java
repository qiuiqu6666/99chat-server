package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chat99.server.push.PushMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserNotificationSettingsServiceTest {

    @Mock UserRepository userRepository;

    UserNotificationSettingsService service;

    @BeforeEach
    void setUp() {
        service = new UserNotificationSettingsService(userRepository);
    }

    @Test
    void maskForUser_showAll_passesThrough() {
        stubUser(NotificationDisplayContent.show_all);
        PushMessage in = PushMessage.of("Alice", "hello");
        PushMessage out = service.maskForUser("u1", in);
        assertThat(out.title()).isEqualTo("Alice");
        assertThat(out.body()).isEqualTo("hello");
    }

    @Test
    void maskForUser_generic_usesPlaceholder() {
        stubUser(NotificationDisplayContent.generic);
        PushMessage out = service.maskForUser("u1", PushMessage.of("Alice", "hello"));
        assertThat(out.title()).isEqualTo("99chat");
        assertThat(out.body()).isEqualTo("你收到了一条消息");
    }

    @Test
    void maskForUser_hidden_hidesDetails() {
        stubUser(NotificationDisplayContent.hidden);
        PushMessage out = service.maskForUser("u1", PushMessage.of("Alice", "hello"));
        assertThat(out.title()).isEqualTo("99chat");
        assertThat(out.body()).isEmpty();
    }

    @Test
    void isSystemMessageNotificationEnabled_defaultsWhenMissing() {
        assertThat(service.isSystemMessageNotificationEnabled("missing")).isTrue();
    }

    private void stubUser(NotificationDisplayContent mode) {
        User user = new User();
        user.setUserId("u1");
        user.setStatus(1);
        user.setSystemMessageNotificationEnabled(true);
        user.setCallNotificationEnabled(true);
        user.setNotificationDisplayContent(mode);
        when(userRepository.findByUserId("u1")).thenReturn(java.util.Optional.of(user));
    }
}
