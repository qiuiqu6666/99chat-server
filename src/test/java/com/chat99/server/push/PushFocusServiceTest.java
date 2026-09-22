package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PushFocusServiceTest {

    @Test
    void encodeFocus_c2c() {
        assertThat(PushFocusService.encodeFocus("c2c", "user123", null))
            .isEqualTo("c2c:user123");
    }

    @Test
    void encodeFocus_group() {
        assertThat(PushFocusService.encodeFocus("group", null, "@TGS#abc"))
            .isEqualTo("group:@TGS#abc");
    }

    @Test
    void encodeFocus_groupAcceptsPeerIdAlias() {
        assertThat(PushFocusService.encodeFocus("GROUP", "@TGS#abc", null))
            .isEqualTo("group:@TGS#abc");
    }

    @Test
    void encodeFocus_rejectsInvalidChatType() {
        assertThatThrownBy(() -> PushFocusService.encodeFocus("channel", "x", null))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void matchesFocus_c2c() {
        assertThat(PushFocusService.matchesFocus("c2c:alice", "c2c", "alice", null)).isTrue();
        assertThat(PushFocusService.matchesFocus("c2c:alice", "c2c", "bob", null)).isFalse();
        assertThat(PushFocusService.matchesFocus("group:g1", "c2c", "alice", null)).isFalse();
    }

    @Test
    void matchesFocus_group() {
        assertThat(PushFocusService.matchesFocus("group:@TGS#1", "group", null, "@TGS#1")).isTrue();
        assertThat(PushFocusService.matchesFocus("group:@TGS#1", "group", null, "@TGS#2")).isFalse();
    }
}
