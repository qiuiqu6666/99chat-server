package com.chat99.server.group;

public record GroupProfileView(
    String groupId,
    String groupType,
    String groupName,
    String displayAlias,
    String avatarUrl,
    String avatarPreviewUrl,
    int avatarVersion,
    String notice,
    int memberCount,
    int memberNum,
    int myRole,
    String myNameCard,
    Long joinedAt,
    long updatedAt,
    String ownerUserId,
    Long noticeUpdatedAt,
    String noticeUpdatedBy,
    boolean gameEnabled,
    String gameid) {

    public static GroupProfileView forList(GroupProfile profile, GroupMember member, boolean gameEnabled) {
        return base(profile, member, false, gameEnabled);
    }

    public static GroupProfileView forDetail(GroupProfile profile, GroupMember member, boolean gameEnabled) {
        return base(profile, member, true, gameEnabled);
    }

    public GroupProfileView withAvatarUrl(String avatarUrl) {
        return new GroupProfileView(
            groupId, groupType, groupName, displayAlias, avatarUrl, avatarPreviewUrl, avatarVersion, notice,
            memberCount, memberNum, myRole, myNameCard, joinedAt, updatedAt, ownerUserId, noticeUpdatedAt,
            noticeUpdatedBy, gameEnabled, gameid);
    }

    public GroupProfileView withMemberCount(int memberCount) {
        int n = Math.max(memberCount, 0);
        return new GroupProfileView(
            groupId, groupType, groupName, displayAlias, avatarUrl, avatarPreviewUrl, avatarVersion, notice,
            n, n, myRole, myNameCard, joinedAt, updatedAt, ownerUserId, noticeUpdatedAt, noticeUpdatedBy,
            gameEnabled, gameid);
    }

    private static GroupProfileView base(
        GroupProfile profile, GroupMember member, boolean detail, boolean gameEnabled) {
        String notice = profile.getNotice() == null ? "" : profile.getNotice();
        Long joinedAt = member.getJoinedAt() == null ? null : member.getJoinedAt().toEpochMilli();
        long updatedAt = profile.getUpdatedAt() == null ? 0L : profile.getUpdatedAt().toEpochMilli();
        Long noticeUpdatedAt = profile.getNoticeUpdatedAt() == null
            ? null
            : profile.getNoticeUpdatedAt().toEpochMilli();
        String noticeUpdatedBy = profile.getNoticeUpdatedBy();
        if (noticeUpdatedBy != null && noticeUpdatedBy.isBlank()) {
            noticeUpdatedBy = null;
        }
        int members = Math.max(profile.getMemberCount(), 0);
        String gameid = profile.getGameid() == null ? "" : profile.getGameid();
        return new GroupProfileView(
            profile.getGroupId(),
            profile.getGroupType(),
            profile.getGroupName(),
            profile.getDisplayAlias(),
            profile.getAvatarUrl(),
            profile.getAvatarPreviewUrl(),
            profile.getAvatarVersion(),
            notice,
            members,
            members,
            member.getRole(),
            member.getNameCard(),
            joinedAt,
            updatedAt,
            detail ? profile.getOwnerUserId() : null,
            detail ? noticeUpdatedAt : null,
            detail ? noticeUpdatedBy : null,
            gameEnabled,
            gameid);
    }
}
