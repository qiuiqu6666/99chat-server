package com.chat99.server.group;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GroupRealtimeDetailFactory {

    private GroupRealtimeDetailFactory() {
    }

    public static Map<String, Object> groupNameChanged(String groupName, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "groupName", groupName);
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    public static Map<String, Object> groupNoticeChanged(String notice,
                                                         Instant noticeUpdatedAt,
                                                         String noticeUpdatedBy,
                                                         Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "notice", notice == null ? "" : notice);
        if (noticeUpdatedAt != null) {
            detail.put("noticeUpdatedAt", noticeUpdatedAt.toEpochMilli());
        }
        put(detail, "noticeUpdatedBy", noticeUpdatedBy);
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    public static Map<String, Object> groupAvatarChanged(String avatarUrl, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "avatarUrl", avatarUrl);
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    /**
     * 展示增量 / TCP：名、头、版本、公告快照（缺省字段不强制）。
     */
    public static Map<String, Object> groupDisplaySnapshot(GroupProfile profile, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (profile != null) {
            put(detail, "groupName", profile.getGroupName());
            put(detail, "avatarUrl", profile.getAvatarUrl());
            put(detail, "avatarPreviewUrl", profile.getAvatarPreviewUrl());
            detail.put("avatarVersion", profile.getAvatarVersion());
            put(detail, "notice", profile.getNotice() == null ? "" : profile.getNotice());
        }
        putUpdatedAt(detail, updatedAt != null ? updatedAt
            : (profile == null ? null : profile.getUpdatedAt()));
        return detail;
    }

    public static Map<String, Object> memberCountChanged(int memberCount, List<String> memberUserIds, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("memberCount", memberCount);
        if (memberUserIds != null && !memberUserIds.isEmpty()) {
            detail.put("memberUserIds", memberUserIds);
        }
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    public static Map<String, Object> memberAddedForExisting(
        int memberCount, List<String> memberUserIds, Instant updatedAt) {
        return memberCountChanged(memberCount, memberUserIds, updatedAt);
    }

    public static Map<String, Object> memberAddedForJoiner(
        GroupProfile profile, GroupMember member, int memberCount, List<String> memberUserIds, Instant updatedAt) {
        Map<String, Object> detail = memberCountChanged(memberCount, memberUserIds, updatedAt);
        put(detail, "groupName", profile.getGroupName());
        put(detail, "groupType", profile.getGroupType());
        put(detail, "displayAlias", profile.getDisplayAlias());
        put(detail, "avatarUrl", profile.getAvatarUrl());
        put(detail, "notice", profile.getNotice() == null ? "" : profile.getNotice());
        if (profile.getNoticeUpdatedAt() != null) {
            detail.put("noticeUpdatedAt", profile.getNoticeUpdatedAt().toEpochMilli());
        }
        put(detail, "noticeUpdatedBy", profile.getNoticeUpdatedBy());
        detail.put("myRole", member.getRole());
        put(detail, "myNameCard", member.getNameCard());
        if (member.getJoinedAt() != null) {
            detail.put("joinedAt", member.getJoinedAt().toEpochMilli());
        }
        return detail;
    }

    public static Map<String, Object> memberProfileChanged(String userId, String nameCard, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "userId", userId);
        put(detail, "nameCard", nameCard);
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    public static Map<String, Object> memberRoleChanged(String userId, int myRole, int previousRole,
                                                        String operatorUserId, Instant updatedAt) {
        Map<String, Object> detail = memberRoleChanged(userId, myRole, updatedAt);
        detail.put("role", myRole);
        detail.put("previousRole", previousRole);
        if (operatorUserId != null) {
            detail.put("operatorUserId", operatorUserId);
        }
        return detail;
    }

    public static Map<String, Object> memberRoleChanged(String userId, int myRole, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "userId", userId);
        detail.put("myRole", myRole);
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    public static Map<String, Object> ownerChanged(String ownerUserId, String oldOwnerUserId, Instant updatedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "ownerUserId", ownerUserId);
        put(detail, "oldOwnerUserId", oldOwnerUserId);
        putUpdatedAt(detail, updatedAt);
        return detail;
    }

    public static Map<String, Object> enrichWithOccurredAt(Map<String, Object> detail, long occurredAtMs) {
        Map<String, Object> out = detail == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detail);
        out.put("updatedAt", occurredAtMs);
        out.put("occurredAt", occurredAtMs);
        return out;
    }

    private static void putUpdatedAt(Map<String, Object> detail, Instant updatedAt) {
        if (updatedAt != null) {
            detail.put("updatedAt", updatedAt.toEpochMilli());
        }
    }

    private static void put(Map<String, Object> detail, String key, String value) {
        if (value != null) {
            detail.put(key, value);
        }
    }
}
