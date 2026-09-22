package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImGroupMentionSupportTest {

    @Test
    void parsesSpecificMention() {
        var mentions = ImGroupMentionSupport.parse(List.of(
            Map.of("GroupAtAllFlag", 0, "GroupAt_Account", "user-1")));

        assertThat(mentions.atAll()).isFalse();
        assertThat(mentions.mentions("user-1")).isTrue();
        assertThat(mentions.mentions("user-2")).isFalse();
    }

    @Test
    void parsesMentionAll() {
        var mentions = ImGroupMentionSupport.parse(List.of(
            Map.of("GroupAtAllFlag", 1)));

        assertThat(mentions.atAll()).isTrue();
        assertThat(mentions.mentions("any-user")).isTrue();
    }

    @Test
    void ignoresPlainTextContainingAtSymbolWithoutGroupAtInfo() {
        var mentions = ImGroupMentionSupport.parse(null);

        assertThat(mentions.atAll()).isFalse();
        assertThat(mentions.mentions("user-1")).isFalse();
    }
}
