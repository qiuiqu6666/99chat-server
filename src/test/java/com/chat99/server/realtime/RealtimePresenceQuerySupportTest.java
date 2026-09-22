package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chat99.server.user.PresenceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimePresenceQuerySupportTest {

    @Test
    void parse_successDedupsAndPreservesOrder() {
        Object parsed = RealtimePresenceQuerySupport.parse(
            Map.of("requestId", "r1", "userIds", List.of("a", "b", "a", "c")),
            200);
        assertThat(parsed).isInstanceOf(RealtimePresenceQuerySupport.ParsedQuery.class);
        var q = (RealtimePresenceQuerySupport.ParsedQuery) parsed;
        assertThat(q.requestId()).isEqualTo("r1");
        assertThat(q.userIds()).containsExactly("a", "b", "c");
    }

    @Test
    void parse_missingRequestId() {
        Object parsed = RealtimePresenceQuerySupport.parse(
            Map.of("userIds", List.of("a")),
            200);
        assertThat(parsed).isEqualTo(new RealtimePresenceQuerySupport.ParseError("INVALID_INPUT"));
    }

    @Test
    void parse_emptyUserIds() {
        Object parsed = RealtimePresenceQuerySupport.parse(
            Map.of("requestId", "r1", "userIds", List.of()),
            200);
        assertThat(parsed).isEqualTo(new RealtimePresenceQuerySupport.ParseError("INVALID_INPUT"));
    }

    @Test
    void parse_batchTooLarge() {
        Object parsed = RealtimePresenceQuerySupport.parse(
            Map.of("requestId", "r1", "userIds", List.of("a", "b", "c")),
            2);
        assertThat(parsed).isEqualTo(new RealtimePresenceQuerySupport.ParseError("BATCH_TOO_LARGE"));
    }

    @Test
    void okAndFailPayloads() {
        var snapshot = new PresenceService.LastSeenSnapshot(
            Map.of("a", 1L),
            Map.of("a", "everyone"));
        Map<String, Object> ok = RealtimePresenceQuerySupport.okPayload("r1", snapshot);
        assertThat(ok.get("type")).isEqualTo("presence_last_seen_ok");
        assertThat(ok.get("requestId")).isEqualTo("r1");
        assertThat(ok.get("lastSeen")).isEqualTo(Map.of("a", 1L));
        assertThat(ok.get("lastActiveVisibility")).isEqualTo(Map.of("a", "everyone"));
        assertThat(ok.get("ts")).isInstanceOf(Long.class);

        Map<String, Object> fail = RealtimePresenceQuerySupport.failPayload("r1", "TOO_MANY_INFLIGHT");
        assertThat(fail).containsEntry("type", "presence_last_seen_fail")
            .containsEntry("requestId", "r1")
            .containsEntry("code", "TOO_MANY_INFLIGHT");
    }
}
