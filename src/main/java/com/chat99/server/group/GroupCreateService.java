package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupCreateService {

    public record JoinOptionsBody(GroupJoinOption applyJoinOption, GroupJoinOption inviteJoinOption) {}

    public record CreateGroupRequest(
        String groupType,
        String groupName,
        String avatarUrl,
        List<String> memberUserIds,
        JoinOptionsBody joinOptions,
        String introduction) {}

    private final ImAdminClient im;
    private final ImUserIdService imUserIdService;
    private final UserFriendService friendService;
    private final UserRepository userRepository;
    private final UserOwnedGroupService ownedGroupService;
    private final GroupJoinLimitService joinLimitService;
    private final GroupProjectionTxService projectionTx;
    private final GroupSettingsRepository settingsRepository;
    private final GroupProfileService profileService;
    private final GroupAvatarDefaults avatarDefaults;

    public GroupCreateService(ImAdminClient im,
                              ImUserIdService imUserIdService,
                              UserFriendService friendService,
                              UserRepository userRepository,
                              UserOwnedGroupService ownedGroupService,
                              GroupJoinLimitService joinLimitService,
                              GroupProjectionTxService projectionTx,
                              GroupSettingsRepository settingsRepository,
                              GroupProfileService profileService,
                              GroupAvatarDefaults avatarDefaults) {
        this.im = im;
        this.imUserIdService = imUserIdService;
        this.friendService = friendService;
        this.userRepository = userRepository;
        this.ownedGroupService = ownedGroupService;
        this.joinLimitService = joinLimitService;
        this.projectionTx = projectionTx;
        this.settingsRepository = settingsRepository;
        this.profileService = profileService;
        this.avatarDefaults = avatarDefaults;
    }

    @Transactional
    public GroupProfileView createGroup(String creatorUserId, CreateGroupRequest req) {
        String groupId = createGroupPersist(creatorUserId, req);
        return profileService.getDetailFresh(groupId, creatorUserId);
    }

    /** IM 建群 + 本地投影/设置写入。响应组装在 {@link #createGroup} 的独立只读事务中读取。 */
    private String createGroupPersist(String creatorUserId, CreateGroupRequest req) {
        validateCreateRequest(req);
        String groupType = req.groupType().trim();
        if (!GroupAccessService.BACKEND_CREATE_GROUP_TYPES.contains(groupType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_TYPE_NOT_SUPPORTED");
        }
        List<String> memberUserIds = normalizeMemberUserIds(req.memberUserIds(), creatorUserId);
        validateInitialMembers(creatorUserId, memberUserIds);
        joinLimitService.assertCanCreateGroup(creatorUserId, groupType, memberUserIds);

        GroupJoinOption apply = req.joinOptions() == null || req.joinOptions().applyJoinOption() == null
            ? GroupJoinOption.need_permission
            : req.joinOptions().applyJoinOption();
        GroupJoinOption invite = req.joinOptions() == null || req.joinOptions().inviteJoinOption() == null
            ? GroupJoinOption.need_permission
            : req.joinOptions().inviteJoinOption();

        String avatarUrl = avatarDefaults.resolve(req.avatarUrl());
        String groupId;
        try {
            List<String> imMemberIds = memberUserIds.stream().map(imUserIdService::toIm).toList();
            groupId = im.createGroup(
                imUserIdService.toIm(creatorUserId),
                groupType,
                req.groupName().trim(),
                avatarUrl,
                req.introduction(),
                imMemberIds,
                apply,
                invite);
        } catch (ImRestException e) {
            throw mapImError(e);
        }

        projectionTx.seedGroupAfterCreate(
            groupId,
            creatorUserId,
            groupType,
            req.groupName().trim(),
            avatarUrl,
            req.introduction(),
            memberUserIds);
        saveJoinOptions(groupId, apply, invite);
        ownedGroupService.recordCreated(creatorUserId, groupType, groupId);

        return groupId;
    }

    private void validateCreateRequest(CreateGroupRequest req) {
        if (req == null
            || req.groupType() == null || req.groupType().isBlank()
            || req.groupName() == null || req.groupName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.memberUserIds() != null && req.memberUserIds().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
    }

    private void validateInitialMembers(String creatorUserId, List<String> memberUserIds) {
        for (String peerId : memberUserIds) {
            requireUserExists(peerId);
            if (!friendService.isMutualActive(creatorUserId, peerId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_FRIEND");
            }
        }
    }

    private void saveJoinOptions(String groupId, GroupJoinOption apply, GroupJoinOption invite) {
        GroupSettings settings = settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings created = new GroupSettings();
            created.setGroupId(groupId);
            return created;
        });
        settings.setApplyJoinOption(apply);
        settings.setInviteJoinOption(invite);
        settingsRepository.save(settings);
    }

    private void requireActiveUser(String userId) {
        userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private void requireUserExists(String userId) {
        userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private static List<String> normalizeMemberUserIds(List<String> memberUserIds, String creatorUserId) {
        if (memberUserIds == null || memberUserIds.isEmpty()) {
            return List.of();
        }
        return memberUserIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.equals(creatorUserId))
            .distinct()
            .toList();
    }

    private static ResponseStatusException mapImError(ImRestException e) {
        if ("IM_NOT_CONFIGURED".equals(e.getMessage())) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IM_NOT_CONFIGURED");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "IM_REST_ERROR");
    }
}
