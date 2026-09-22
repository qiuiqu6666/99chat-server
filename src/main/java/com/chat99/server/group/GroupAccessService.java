package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.im.restqueue.ImRestQueueProperties;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupAccessService {

    static final Set<String> BACKEND_INVITE_GROUP_TYPES = Set.of("Public", "Meeting", "Community");
    static final Set<String> BACKEND_CREATE_GROUP_TYPES = Set.of("Public", "Meeting", "Community", "Work");

    private final ImAdminClient im;
    private final GroupMemberRepository memberRepository;
    private final GroupProfileRepository profileRepository;
    private final ImGroupIdRemapLookup groupIdRemapLookup;
    private final ImUserIdService imUserIdService;
    private final ObjectProvider<ImRestQueuePublisher> queuePublisher;
    private final ObjectProvider<ImRestQueueProperties> queueProps;

    public GroupAccessService(ImAdminClient im,
                              GroupMemberRepository memberRepository,
                              GroupProfileRepository profileRepository,
                              ImGroupIdRemapLookup groupIdRemapLookup,
                              ImUserIdService imUserIdService,
                              ObjectProvider<ImRestQueuePublisher> queuePublisher,
                              ObjectProvider<ImRestQueueProperties> queueProps) {
        this.im = im;
        this.memberRepository = memberRepository;
        this.profileRepository = profileRepository;
        this.groupIdRemapLookup = groupIdRemapLookup;
        this.imUserIdService = imUserIdService;
        this.queuePublisher = queuePublisher;
        this.queueProps = queueProps;
    }

    public GroupAdminInfo requireGroupInfo(String groupId) {
        validateGroupId(groupId);
        String gid = groupId.trim();
        Optional<GroupProfile> local = profileRepository.findById(gid);
        if (local.isPresent()) {
            GroupProfile profile = local.get();
            if (profile.isDismissed()) {
                maybeEnqueueVerify(gid, "require_group_dismissed");
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
            }
            return toAdminInfo(profile);
        }
        // 本地无资料：低频路径才同步拉一次；失败不映射为解散
        Optional<GroupAdminInfo> imInfo = im.fetchGroupAdminInfo(gid);
        if (imInfo.isPresent()) {
            maybeEnqueueVerify(gid, "require_group_miss");
            return imInfo.get();
        }
        maybeEnqueueVerify(gid, "require_group_im_miss");
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    private static GroupAdminInfo toAdminInfo(GroupProfile profile) {
        return new GroupAdminInfo(
            profile.getGroupId(),
            profile.getGroupName(),
            profile.getOwnerUserId(),
            profile.getMemberCount(),
            null,
            profile.getCreatedAt() == null ? null : profile.getCreatedAt().getEpochSecond(),
            profile.getAvatarPreviewUrl() != null ? profile.getAvatarPreviewUrl() : profile.getAvatarUrl(),
            null,
            profile.getGroupType(),
            null,
            null,
            profile.getNotice());
    }

    private void maybeEnqueueVerify(String groupId, String reason) {
        ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
        if (publisher != null) {
            publisher.enqueueVerifyGroupExists(groupId, reason);
        }
    }

    public GroupAdminInfo requireBackendInviteGroup(String groupId) {
        GroupAdminInfo info = requireGroupInfo(groupId);
        if (info.type() == null || !BACKEND_INVITE_GROUP_TYPES.contains(info.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_TYPE_NOT_SUPPORTED");
        }
        return info;
    }

    public String requireMemberRole(String groupId, String userId) {
        String role = resolveLocalRole(groupId, userId);
        if (role != null) {
            maybeEnqueueRoleRefresh(groupId, userId, "auth_hit");
            return role;
        }
        if (localAuthImFallbackEnabled()) {
            String imRole = im.getRoleInGroup(groupId, userId);
            if (imRole != null && !"NotMember".equals(imRole)) {
                return imRole;
            }
        }
        maybeEnqueueHydrate(groupId, userId, "auth_miss");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
    }

    public void requireMember(String groupId, String userId) {
        requireMemberRole(groupId, userId);
    }

    public String normalizeGroupId(String groupId) {
        validateGroupId(groupId);
        String gid = groupId.trim();
        return groupIdRemapLookup.findDstBySrc(gid).orElse(gid);
    }

    public String requireAdminRole(String groupId, String userId) {
        String role = requireMemberRole(groupId, userId);
        if (!"Owner".equals(role) && !"Admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");
        }
        return role;
    }

    public void requireOwnerRole(String groupId, String userId) {
        String role = requireMemberRole(groupId, userId);
        if (!"Owner".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        }
    }

    public boolean isAdminRole(String role) {
        return "Owner".equals(role) || "Admin".equals(role);
    }

    public boolean isMember(String groupId, String userId) {
        String local = resolveLocalRole(groupId, userId);
        if (local != null) {
            maybeEnqueueRoleRefresh(groupId, userId, "is_member_hit");
            return true;
        }
        if (localAuthImFallbackEnabled()) {
            String role = im.getRoleInGroup(groupId, userId);
            return role != null && !"NotMember".equals(role);
        }
        maybeEnqueueHydrate(groupId, userId, "is_member_miss");
        return false;
    }

    /**
     * 仅本地投影点查（业务号 + IM 号），不打 IM REST。用于群转账等热路径。
     */
    public boolean isLocalMember(String groupId, String userId) {
        validateGroupId(groupId);
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String gid = groupId.trim();
        String uid = userId.trim();
        Optional<GroupProfile> profile = profileRepository.findById(gid);
        if (profile.isPresent() && profile.get().isDismissed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        if (memberRepository.findByGroupIdAndUserIdActive(gid, uid).isPresent()) {
            maybeEnqueueRoleRefresh(gid, uid, "local_member_hit");
            return true;
        }
        String imId = imUserIdService.toIm(uid);
        if (imId != null && !imId.isBlank() && !imId.equals(uid)
            && memberRepository.findByGroupIdAndUserIdActive(gid, imId.trim()).isPresent()) {
            maybeEnqueueRoleRefresh(gid, uid, "local_member_im_hit");
            return true;
        }
        maybeEnqueueHydrate(gid, uid, "local_member_miss");
        return false;
    }

    public void requireLocalMember(String groupId, String userId) {
        if (!isLocalMember(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        }
    }

    private String resolveLocalRole(String groupId, String userId) {
        validateGroupId(groupId);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        }
        String gid = groupId.trim();
        String uid = userId.trim();
        Optional<GroupProfile> profile = profileRepository.findById(gid);
        if (profile.isPresent() && profile.get().isDismissed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        return memberRepository.findByGroupIdAndUserIdActive(gid, uid)
            .map(m -> GroupRoleCodec.toImRole(m.getRole()))
            .orElse(null);
    }

    private boolean localAuthImFallbackEnabled() {
        ImRestQueueProperties props = queueProps.getIfAvailable();
        return props != null && Boolean.TRUE.equals(props.localAuthImFallback());
    }

    private void maybeEnqueueRoleRefresh(String groupId, String userId, String reason) {
        ImRestQueueProperties props = queueProps.getIfAvailable();
        ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
        if (props == null || publisher == null || !Boolean.TRUE.equals(props.enqueueRoleRefreshOnAuth())) {
            return;
        }
        publisher.enqueueRefreshRole(groupId, userId, reason);
    }

    private void maybeEnqueueHydrate(String groupId, String userId, String reason) {
        ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
        if (publisher == null) {
            return;
        }
        publisher.enqueueHydrateGroup(groupId, userId, reason);
    }

    static void validateGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }
}
