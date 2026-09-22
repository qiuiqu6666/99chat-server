package com.chat99.server.realtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class GroupRealtimePublisher {

    public static final String EVENT = "group_changed";

    public static final String ACTION_GROUP_NOTICE_CHANGED = "group_notice_changed";
    public static final String ACTION_GROUP_NAME_CHANGED = "group_name_changed";
    public static final String ACTION_GROUP_AVATAR_CHANGED = "group_avatar_changed";
    public static final String ACTION_GROUP_MUTE_ALL_CHANGED = "group_mute_all_changed";
    public static final String ACTION_GROUP_JOIN_OPTION_CHANGED = "group_join_option_changed";
    public static final String ACTION_JOIN_APPLICATION_PENDING = "join_application_pending";
    public static final String ACTION_JOIN_APPLICATION_HANDLED = "join_application_handled";
    public static final String ACTION_GROUP_PRIVACY_CHANGED = "group_privacy_changed";
    public static final String ACTION_MEMBER_ADDED = "member_added";
    public static final String ACTION_MEMBER_REMOVED = "member_removed";
    public static final String ACTION_MEMBER_LEFT = "member_left";
    public static final String ACTION_MEMBER_ROLE_CHANGED = "member_role_changed";
    public static final String ACTION_MEMBER_MUTED = "member_muted";
    public static final String ACTION_MEMBER_UNMUTED = "member_unmuted";
    public static final String ACTION_MEMBER_PROFILE_CHANGED = "member_profile_changed";
    public static final String ACTION_OWNER_CHANGED = "owner_changed";
    public static final String ACTION_GROUP_SYSTEM_NOTICE = "group_system_notice";
    public static final String ACTION_GROUP_LIVE_CHANGED = "group_live_changed";
    public static final String ACTION_GROUP_DISMISSED = "group_dismissed";

    public record GroupChangedEvent(
        String groupId,
        String action,
        String operatorUserId,
        List<String> memberUserIds,
        List<String> targetUserIds,
        Map<String, Object> detail,
        String changeEventId,
        long occurredAt,
        Integer timelineRank) {}

    private final ApplicationEventPublisher events;

    public GroupRealtimePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publish(String groupId,
                        String action,
                        String operatorUserId,
                        List<String> memberUserIds,
                        List<String> targetUserIds,
                        Map<String, Object> detail,
                        String changeEventId,
                        long occurredAt,
                        Integer timelineRank) {
        if (groupId == null || groupId.isBlank() || action == null || action.isBlank()) {
            return;
        }
        if (changeEventId == null || changeEventId.isBlank()) {
            return;
        }
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return;
        }
        Map<String, Object> safeDetail = detail == null ? Map.of() : new LinkedHashMap<>(detail);
        events.publishEvent(new GroupChangedEvent(
            groupId.trim(),
            action,
            operatorUserId,
            memberUserIds == null ? List.of() : memberUserIds,
            targetUserIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList(),
            safeDetail,
            changeEventId.trim(),
            occurredAt,
            timelineRank));
    }
}
