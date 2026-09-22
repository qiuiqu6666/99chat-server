package com.chat99.server.messagearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatMessageTableRouterTest {

    private final ChatMessageTableRouter router = new ChatMessageTableRouter();

    @Test
    void routesByUtcMonth() {
        Instant june = Instant.parse("2026-06-15T10:00:00Z");
        assertEquals("chat_message_202606", router.physicalTable(june));
        assertEquals("202606", router.tableSuffix(june));
    }

    @Test
    void physicalTablesAroundIncludesCurrentAndPreviousMonths() {
        Instant june = Instant.parse("2026-06-15T10:00:00Z");
        assertEquals(
            java.util.List.of("chat_message_202606", "chat_message_202605", "chat_message_202604"),
            router.physicalTablesAround(june, 2));
    }

    @Test
    void physicalTablesBetweenCoversUtcMonthsNewestFirst() {
        long from = Instant.parse("2026-05-10T00:00:00Z").toEpochMilli();
        long to = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli();
        assertEquals(
            java.util.List.of("chat_message_202607", "chat_message_202606", "chat_message_202605"),
            router.physicalTablesBetween(from, to));
    }
}
