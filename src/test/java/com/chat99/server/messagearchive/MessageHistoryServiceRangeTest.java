package com.chat99.server.messagearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MessageHistoryServiceRangeTest {

    private JdbcTemplate jdbc;
    private ChatHistoryClearService clearService;
    private MessageHistoryService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        clearService = mock(ChatHistoryClearService.class);
        when(clearService.clearedBeforeMsC2c(anyString(), anyString())).thenReturn(0L);
        when(clearService.clearedBeforeMsGroup(anyString(), anyString())).thenReturn(0L);
        service = new MessageHistoryService(jdbc, new ChatMessageTableRouter(), clearService, new ObjectMapper());
    }

    @Test
    void c2cRequiresBothTimeBounds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.listC2c("u1", "u2", null, 1000L, null, 40));
        assertTrue(ex.getMessage().contains("fromTimeMs"));
    }

    @Test
    void groupRequiresBothSeqBounds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.listGroup("u1", "@TGS#x", null, 12L, null, 40));
        assertTrue(ex.getMessage().contains("fromSeq"));
    }

    @Test
    void c2cTimeRangeReturnsAscending() {
        MessageHistoryService.HistoryItem a = item("a", null, 1000L);
        MessageHistoryService.HistoryItem b = item("b", null, 2000L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(a, b))
            .thenReturn(List.of());

        MessageHistoryService.HistoryPage page =
            service.listC2c("u1", "u2", null, 1000L, 3000L, 40);

        assertEquals(2, page.items().size());
        assertEquals(1000L, page.items().get(0).msgTimeMs());
        assertEquals(2000L, page.items().get(1).msgTimeMs());
        assertEquals(false, page.hasMore());
    }

    @Test
    void groupSeqRangeReturnsAscendingAndNextCursor() {
        List<MessageHistoryService.HistoryItem> rows = List.of(
            item("g:12", 12L, 1000L),
            item("g:13", 13L, 1100L),
            item("g:14", 14L, 1200L));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
            .thenReturn(rows)
            .thenReturn(List.of());

        MessageHistoryService.HistoryPage page =
            service.listGroup("u1", "@TGS#x", null, 12L, 20L, 2);

        assertEquals(2, page.items().size());
        assertEquals(12L, page.items().get(0).msgSeq());
        assertEquals(13L, page.items().get(1).msgSeq());
        assertEquals(true, page.hasMore());
        assertEquals(13L, page.nextCursor());
    }

    private static MessageHistoryService.HistoryItem item(String key, Long seq, long timeMs) {
        return new MessageHistoryService.HistoryItem(
            key, null, "from", "peer", "@TGS#x", seq, timeMs, "TIMTextElem", "hi", List.of(Map.of()), 1);
    }
}
