package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import java.util.Map;

public final class JoinApplicationMapper {

    private JoinApplicationMapper() {
    }

    public record JoinApplicationItem(
        long applicationId,
        String groupId,
        String applicationType,
        String fromUserId,
        String toUserId,
        String message,
        String status,
        Long createdAt,
        Long handledAt,
        String fromUserNickName,
        String toUserNickName,
        String fromUserFaceUrl,
        String groupName,
        String groupAvatarUrl,
        String handlerUserId,
        String handledByUserId,
        String handledByNickName,
        /** inviter / invitee / applicant / admin — 当前用户在此条记录中的视角 */
        String viewerRole,
        /** 是否展示同意/拒绝按钮；邀请人看自己发起的 pending 时为 false */
        boolean canHandle) {

        public long id() {
            return applicationId;
        }

        public String type() {
            return applicationType;
        }
    }

    public static String toApiApplicationType(GroupJoinApplicationType type) {
        return type == GroupJoinApplicationType.apply ? "join" : "invite";
    }

    public static GroupJoinApplicationStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toLowerCase()) {
            case "pending" -> GroupJoinApplicationStatus.pending;
            case "approved", "accepted", "added" -> GroupJoinApplicationStatus.approved;
            case "rejected", "declined" -> GroupJoinApplicationStatus.rejected;
            default -> null;
        };
    }

    public static JoinApplicationItem toItem(GroupJoinApplication app,
                                             Map<String, ImAdminClient.ProfilePortrait> profiles,
                                             Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs,
                                             GroupProfile profile,
                                             String avatarUrl) {
        return toItem(app, profiles, localBriefs, profile, avatarUrl, null, null);
    }

    public static JoinApplicationItem toItem(GroupJoinApplication app,
                                             Map<String, ImAdminClient.ProfilePortrait> profiles,
                                             Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs,
                                             GroupProfile profile,
                                             String avatarUrl,
                                             String viewerUserId,
                                             Integer myRoleInGroup) {
        String fromNick = resolveNick(app.getFromUserId(), profiles, localBriefs);
        String toNick = app.getType() == GroupJoinApplicationType.apply
            ? null
            : resolveNick(app.getToUserId(), profiles, localBriefs);
        String fromFace = resolveFace(app.getFromUserId(), profiles, localBriefs);
        String groupName = profile == null ? null : profile.getGroupName();
        Long createdAt = app.getCreatedAt() == null ? null : app.getCreatedAt().toEpochMilli();
        Long handledAt = app.getHandledAt() == null ? null : app.getHandledAt().toEpochMilli();
        String handledByNickName = resolveNick(app.getHandledBy(), profiles, localBriefs);
        String viewerRole = resolveViewerRole(viewerUserId, app, myRoleInGroup);
        boolean canHandle = canHandle(viewerUserId, app, myRoleInGroup);
        return new JoinApplicationItem(
            app.getId(),
            app.getGroupId(),
            toApiApplicationType(app.getType()),
            app.getFromUserId(),
            app.getType() == GroupJoinApplicationType.apply ? null : app.getToUserId(),
            app.getMessage(),
            app.getStatus().name(),
            createdAt,
            handledAt,
            fromNick,
            toNick,
            fromFace,
            groupName,
            avatarUrl,
            app.getHandledBy(),
            app.getHandledBy(),
            handledByNickName,
            viewerRole,
            canHandle);
    }

    public static String resolveViewerRole(String viewerUserId,
                                           GroupJoinApplication app,
                                           Integer myRoleInGroup) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return null;
        }
        if (app.getType() == GroupJoinApplicationType.apply && viewerUserId.equals(app.getFromUserId())) {
            return "applicant";
        }
        if (app.getType() == GroupJoinApplicationType.invite && viewerUserId.equals(app.getFromUserId())) {
            return "inviter";
        }
        if (app.getType() == GroupJoinApplicationType.invite && viewerUserId.equals(app.getToUserId())) {
            return "invitee";
        }
        if (myRoleInGroup != null && myRoleInGroup >= GroupRoleCodec.ADMIN) {
            return "admin";
        }
        return null;
    }

    public static boolean canHandle(String viewerUserId,
                                    GroupJoinApplication app,
                                    Integer myRoleInGroup) {
        if (app.getStatus() != GroupJoinApplicationStatus.pending) {
            return false;
        }
        if (viewerUserId == null || viewerUserId.isBlank()
            || myRoleInGroup == null || myRoleInGroup < GroupRoleCodec.ADMIN) {
            return false;
        }
        if (app.getType() == GroupJoinApplicationType.invite && viewerUserId.equals(app.getFromUserId())) {
            return false;
        }
        return true;
    }

    private static String resolveNick(String userId,
                                      Map<String, ImAdminClient.ProfilePortrait> profiles,
                                      Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        if (localBriefs != null) {
            GroupMemberEnrichmentService.UserBrief brief = localBriefs.get(userId);
            if (brief != null && brief.nickname() != null && !brief.nickname().isBlank()) {
                return brief.nickname();
            }
        }
        if (profiles != null) {
            ImAdminClient.ProfilePortrait profile = profiles.get(userId.trim());
            if (profile != null && profile.nickname() != null && !profile.nickname().isBlank()) {
                return profile.nickname();
            }
        }
        return null;
    }

    private static String resolveFace(String userId,
                                      Map<String, ImAdminClient.ProfilePortrait> profiles,
                                      Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        if (localBriefs != null) {
            GroupMemberEnrichmentService.UserBrief brief = localBriefs.get(userId);
            if (brief != null && brief.avatarUrl() != null && !brief.avatarUrl().isBlank()) {
                return brief.avatarUrl();
            }
        }
        if (profiles != null) {
            ImAdminClient.ProfilePortrait profile = profiles.get(userId.trim());
            if (profile != null && profile.imageUrl() != null && !profile.imageUrl().isBlank()) {
                return profile.imageUrl();
            }
        }
        return null;
    }
}
