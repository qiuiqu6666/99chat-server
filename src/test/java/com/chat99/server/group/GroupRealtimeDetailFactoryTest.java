package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupRealtimeDetailFactoryTest {

    @Test
    void groupNameDetailUsesRestFieldNames() {
        Instant ts = Instant.parse("2026-06-17T00:00:00Z");
        var detail = GroupRealtimeDetailFactory.groupNameChanged("新群名", ts);
        assertThat(detail.get("groupName")).isEqualTo("新群名");
        assertThat(detail.get("updatedAt")).isEqualTo(ts.toEpochMilli());
    }

    @Test
    void memberCountDetailIncludesIds() {
        var detail = GroupRealtimeDetailFactory.memberCountChanged(
            3, List.of("u1", "u2"), Instant.EPOCH);
        assertThat(detail.get("memberCount")).isEqualTo(3);
        assertThat(detail.get("memberUserIds")).isEqualTo(List.of("u1", "u2"));
    }

    @Test
    void enrichWithOccurredAt_alignsUpdatedAt() {
        var detail = GroupRealtimeDetailFactory.enrichWithOccurredAt(Map.of("memberCount", 2), 1718592000123L);
        assertThat(detail.get("updatedAt")).isEqualTo(1718592000123L);
        assertThat(detail.get("occurredAt")).isEqualTo(1718592000123L);
    }
}
