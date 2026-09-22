package com.chat99.server.messagearchive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImMessageArchiveParserMsgIdTest {

    @Test
    void genuineMsgId_rejectsBlankAndMsgKeyLookalike() {
        assertThat(ImMessageArchiveParser.genuineMsgId(null, "k")).isNull();
        assertThat(ImMessageArchiveParser.genuineMsgId(" ", "k")).isNull();
        assertThat(ImMessageArchiveParser.genuineMsgId("same", "same")).isNull();
        assertThat(ImMessageArchiveParser.genuineMsgId(
            "144115268026882536-1784319889-1876410779",
            "3358721060_1876410779_1784319889"))
            .isEqualTo("144115268026882536-1784319889-1876410779");
    }
}
