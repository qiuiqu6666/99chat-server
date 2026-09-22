package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupChangedPayloadBuilderTest {

    @Test
    void buildIncludesChangeMetadata() {
        GroupRealtimePublisher.GroupChangedEvent event = new GroupRealtimePublisher.GroupChangedEvent(
            "@TGS#abc",
            GroupRealtimePublisher.ACTION_MEMBER_ADDED,
            "userA",
            List.of("userB"),
            List.of("userA", "userB"),
            Map.of("memberCount", 2, "updatedAt", 1000L, "occurredAt", 1000L),
            "ce_test123",
            1000L,
            30);

        Map<String, Object> payload = GroupChangedPayloadBuilder.build(event);

        assertThat(payload.get("event")).isEqualTo("group_changed");
        assertThat(payload.get("changeEventId")).isEqualTo("ce_test123");
        assertThat(payload.get("occurredAt")).isEqualTo(1000L);
        assertThat(payload.get("timelineRank")).isEqualTo(30);
        assertThat(payload.get("memberUserIds")).isEqualTo(List.of("userB"));
    }
}
