package com.chat99.server.group;

import com.chat99.server.realtime.GroupRealtimePublisher;

public final class GroupTimelineRank {

    private GroupTimelineRank() {
    }

    public static Integer forAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case GroupRealtimePublisher.ACTION_MEMBER_ADDED -> 30;
            case GroupRealtimePublisher.ACTION_MEMBER_REMOVED,
                 GroupRealtimePublisher.ACTION_MEMBER_LEFT -> 40;
            default -> null;
        };
    }
}
