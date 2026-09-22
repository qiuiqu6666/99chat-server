package com.chat99.server.messagearchive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SuperGroupHistoryServiceTest {
    @Mock JdbcTemplate jdbc;
    private SuperGroupHistoryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SuperGroupHistoryService(jdbc, new ChatMessageTableRouter(), new ObjectMapper(), "test-secret");
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("chat_message_202609"));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(105L);
    }

    @Test
    void firstPageUsesSeqKeysetAndReturnsAscendingItems() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
            .thenReturn(List.of(row("m105", 105L), row("m104", 104L), row("m103", 103L)));
        var response = service.older("u1", "g1", 2, null);
        assertThat(response.items()).extracting(SuperGroupHistoryService.HistoryItem::groupSeq)
            .containsExactly(104L, 105L);
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.hasMoreOlder()).isTrue();
        assertThat(response.olderCursor()).isNotBlank();
    }

    @Test
    void cursorCannotBeUsedByAnotherUser() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
            .thenReturn(List.of(row("m105", 105L), row("m104", 104L), row("m103", 103L)));
        var first = service.older("u1", "g1", 2, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.older("u2", "g1", 2, first.olderCursor()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("CURSOR_USER_MISMATCH");
    }

    private static SuperGroupHistoryService.HistoryItem row(String id, long seq) {
        return new SuperGroupHistoryService.HistoryItem(id, seq, "sender", "text", java.util.Map.of(), seq, 1);
    }
}
