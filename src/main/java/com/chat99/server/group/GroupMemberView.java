package com.chat99.server.group;

public record GroupMemberView(
    String userId,
    String imUserId,
    String nickname,
    String avatarUrl,
    String friendRemark,
    String nameCard,
    int role,
    String roleName,
    Long joinedAt,
    boolean isSelf,
    String invitedByUserId,
    String invitedByNickname,
    String joinChannel) {}
