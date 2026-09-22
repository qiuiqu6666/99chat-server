package com.chat99.server.realtime;

import com.chat99.server.realtime.GroupRealtimePublisher.GroupChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GroupChangedPayloadBuilder {

    private GroupChangedPayloadBuilder() {
    }

    public static Map<String, Object> build(GroupChangedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", GroupRealtimePublisher.EVENT);
        payload.put("groupId", event.groupId());
        payload.put("action", event.action());
        if (event.operatorUserId() != null && !event.operatorUserId().isBlank()) {
            payload.put("operatorUserId", event.operatorUserId());
        }
        if (event.memberUserIds() != null && !event.memberUserIds().isEmpty()) {
            payload.put("memberUserIds", event.memberUserIds());
        }
        payload.put("changeEventId", event.changeEventId());
        payload.put("occurredAt", event.occurredAt());
        if (event.timelineRank() != null) {
            payload.put("timelineRank", event.timelineRank());
        }
        if (event.detail() != null && !event.detail().isEmpty()) {
            payload.put("detail", event.detail());
        }
        return payload;
    }
}
