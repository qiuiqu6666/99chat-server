package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupProfileService {

    /** 列表页硬上限；默认 limit 见 MeGroupsController（更小，削峰）。 */
    static final int MAX_LIST_LIMIT = 200;

    private final GroupProjectionService projection;
    private final GroupProfileRepository profileRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupSettingsRepository settingsRepository;
    private final GroupAccessService access;
    private final ImAdminClient im;
    private final GroupChangeEmitter groupChangeEmitter;
    private final GroupAvatarDefaults avatarDefaults;
    private final GroupGameService groupGameService;
    private final ObjectProvider<ImRestQueuePublisher> restQueue;
    private final MeGroupsListCache meGroupsListCache;
    private final GroupFanoutTargetResolver fanoutTargets;
    private final GroupImSyncService groupImSyncService;
    private final int maxListLimit;

    public GroupProfileService(GroupProjectionService projection,
                               GroupProfileRepository profileRepository,
                               GroupMemberRepository memberRepository,
                               GroupSettingsRepository settingsRepository,
                               GroupAccessService access,
                               ImAdminClient im,
                               GroupChangeEmitter groupChangeEmitter,
                               GroupAvatarDefaults avatarDefaults,
                               GroupGameService groupGameService,
                               ObjectProvider<ImRestQueuePublisher> restQueue,
                               MeGroupsListCache meGroupsListCache,
                               GroupFanoutTargetResolver fanoutTargets,
                               GroupImSyncService groupImSyncService,
                               @Value("${chat99.group.me-groups-max-limit:200}") int maxListLimit) {
        this.projection = projection;
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
        this.settingsRepository = settingsRepository;
        this.access = access;
        this.im = im;
        this.groupChangeEmitter = groupChangeEmitter;
        this.avatarDefaults = avatarDefaults;
        this.groupGameService = groupGameService;
        this.restQueue = restQueue;
        this.meGroupsListCache = meGroupsListCache;
        this.fanoutTargets = fanoutTargets;
        this.groupImSyncService = groupImSyncService;
        this.maxListLimit = Math.min(Math.max(maxListLimit, 1), MAX_LIST_LIMIT);
    }

    public MyGroupsResponse listMyGroups(String userId, int limit, int offset, boolean refresh) {
        int safeLimit = Math.min(Math.max(limit, 1), maxListLimit);
        int safeOffset = Math.max(offset, 0);

        // refresh 强制回源；短缓存仅服务常态读
        if (!refresh) {
            Optional<MyGroupsResponse> cached = meGroupsListCache.get(userId, safeLimit, safeOffset);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 热路径只读本地投影；refresh / 冷启动仅异步入队，禁止同步打爆 IM REST
        if (refresh) {
            enqueueJoinedSync(userId, "me_groups_refresh", true);
        }

        Page<GroupMember> page = memberRepository.findMyGroups(
            userId, PageRequest.of(safeOffset / safeLimit, safeLimit));
        long total = page.getTotalElements();
        if (!refresh && safeOffset == 0 && total == 0) {
            enqueueJoinedSync(userId, "me_groups_cold", false);
        }

        List<GroupProfileView> items = toListViews(page.getContent());
        MyGroupsResponse response = new MyGroupsResponse(items, total, safeLimit, safeOffset);
        if (!refresh) {
            meGroupsListCache.put(userId, safeLimit, safeOffset, response);
        } else {
            meGroupsListCache.invalidateUser(userId);
        }
        return response;
    }

    private void enqueueJoinedSync(String userId, String reason, boolean syncIfNoQueue) {
        ImRestQueuePublisher publisher = restQueue.getIfAvailable();
        if (publisher != null) {
            publisher.enqueueSyncUserJoined(userId, reason);
        } else if (syncIfNoQueue) {
            projection.syncJoinedGroupsFromIm(userId);
        }
    }

    private List<GroupProfileView> toListViews(List<GroupMember> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        Set<String> groupIds = new LinkedHashSet<>();
        for (GroupMember member : members) {
            if (member.getGroupId() != null && !member.getGroupId().isBlank()) {
                groupIds.add(member.getGroupId());
            }
        }
        Map<String, GroupProfile> profiles = new HashMap<>();
        for (GroupProfile profile : profileRepository.findAllById(groupIds)) {
            if (!profile.isDismissed()) {
                profiles.put(profile.getGroupId(), profile);
            }
        }
        Map<String, Boolean> gameByGroup = new HashMap<>();
        for (GroupSettings settings : settingsRepository.findAllById(groupIds)) {
            gameByGroup.put(settings.getGroupId(), settings.isGameEnabled());
        }
        List<GroupProfileView> items = new ArrayList<>(members.size());
        for (GroupMember member : members) {
            GroupProfile profile = profiles.get(member.getGroupId());
            if (profile == null) {
                continue;
            }
            boolean gameEnabled = Boolean.TRUE.equals(gameByGroup.get(member.getGroupId()));
            items.add(avatarDefaults.applyToView(
                GroupProfileView.forList(profile, member, gameEnabled)));
        }
        return items;
    }

    public GroupProfileView getDetail(String groupId, String userId) {
        if (projection.isLocallyDismissed(groupId)) {
            // 可能是假解散残留：异步校验，请求线程绝不同步解散/恢复
            ImRestQueuePublisher publisher = restQueue.getIfAvailable();
            if (publisher != null) {
                publisher.enqueueVerifyGroupExists(groupId, "getDetail_local_dismissed");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        access.requireMember(groupId, userId);
        Optional<GroupMember> local = projection.findMember(groupId, userId);
        if (local.isEmpty()) {
            ImRestQueuePublisher publisher = restQueue.getIfAvailable();
            if (publisher != null) {
                publisher.enqueueHydrateGroup(groupId, userId, "getDetail_miss");
                publisher.enqueueVerifyGroupExists(groupId, "getDetail_miss");
            } else {
                ensureHydrated(groupId, userId);
                local = projection.findMember(groupId, userId);
            }
            if (local.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
            }
        } else if (local.get().isDeleted()) {
            local = Optional.empty();
            ImRestQueuePublisher publisher = restQueue.getIfAvailable();
            if (publisher != null) {
                publisher.enqueueHydrateGroup(groupId, userId, "getDetail_tombstone");
                publisher.enqueueVerifyGroupExists(groupId, "getDetail_tombstone");
            } else {
                ensureHydrated(groupId, userId);
                local = projection.findMember(groupId, userId);
            }
            if (local.isEmpty() || local.get().isDeleted()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
            }
        } else {
            // 后台校正成员身份，不阻塞读（REFRESH_ROLE 在 IM NotMember 时会软删本地）
            ImRestQueuePublisher publisher = restQueue.getIfAvailable();
            if (publisher != null) {
                publisher.enqueueRefreshRole(groupId, userId, "getDetail_bg");
                publisher.enqueueVerifyGroupExists(groupId, "getDetail_bg");
            } else {
                ensureHydrated(groupId, userId);
                local = projection.findMember(groupId, userId);
                if (local.isEmpty() || local.get().isDeleted()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
                }
            }
        }
        // 与 GET /me/groups 一致：人数只读 group_profile.member_count，不用本地活成员数垫高
        return toView(local.get(), true);
    }

    /**
     * {@code POST /group} 建群后立即读详情：独立只读事务，避免外层 RR 快照看不到
     * {@link GroupProjectionTxService#syncFullGroupFromIm}（REQUIRES_NEW）已提交的行而重复 insert。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GroupProfileView getDetailFresh(String groupId, String userId) {
        return getDetail(groupId, userId);
    }

    @Transactional
    public GroupProfileView updateProfile(String groupId, String userId, ProfileUpdateRequest req) {
        access.requireAdminRole(groupId, userId);
        ensureHydrated(groupId, userId);
        GroupProfile profile = requireProfile(groupId);
        boolean nameChanged = false;
        boolean noticeChanged = false;
        String newName = null;
        String newNotice = null;
        if (req.groupName() != null && !req.groupName().isBlank()
            && !req.groupName().equals(profile.getGroupName())) {
            newName = req.groupName().trim();
            profile.setGroupName(newName);
            nameChanged = true;
        }
        if (req.notice() != null && !req.notice().equals(profile.getNotice())) {
            Instant noticeAt = Instant.now();
            newNotice = req.notice();
            profile.setNotice(newNotice);
            profile.setNoticeUpdatedAt(noticeAt);
            profile.setNoticeUpdatedBy(userId);
            noticeChanged = true;
        }
        if (!nameChanged && !noticeChanged) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        profileRepository.save(profile);
        groupImSyncService.syncGroupBaseInfo(
            groupId,
            nameChanged ? newName : null,
            noticeChanged ? newNotice : null);
        if (nameChanged) {
            publishDisplay(groupId, userId, GroupRealtimePublisher.ACTION_GROUP_NAME_CHANGED);
        }
        if (noticeChanged) {
            publishDisplay(groupId, userId, GroupRealtimePublisher.ACTION_GROUP_NOTICE_CHANGED);
        }
        meGroupsListCache.invalidateGroup(groupId);
        return toView(requireMemberRow(groupId, userId), true);
    }

    @Transactional
    public GroupProfileView updateMyNameCard(String groupId, String userId, String nameCard) {
        access.requireMember(groupId, userId);
        ensureHydrated(groupId, userId);
        GroupMember member = requireMemberRow(groupId, userId);
        member.setNameCard(nameCard);
        memberRepository.save(member);
        try {
            im.modifyMemberNameCard(groupId, userId, nameCard);
        } catch (ImRestException e) {
            throw mapImError(e);
        }
        List<String> targets = fanoutTargets.resolveMemberTargets(groupId);
        groupChangeEmitter.emitGroupChanged(
            groupId,
            GroupRealtimePublisher.ACTION_MEMBER_PROFILE_CHANGED,
            userId,
            List.of(userId),
            targets,
            GroupRealtimeDetailFactory.memberProfileChanged(userId, nameCard, member.getUpdatedAt()),
            Instant.now(),
            GroupChangeEventSource.REST_PROFILE);
        meGroupsListCache.invalidateUser(userId);
        return toView(member, true);
    }

    @Transactional
    public GroupProfileView updateAvatar(String groupId, String userId, GroupAvatarService.UploadResult upload) {
        // Controller 已 requireAdminRole；此处不再二次打 IM 鉴权
        ensureHydrated(groupId, userId);
        GroupProfile profile = requireProfile(groupId);
        profile.setAvatarUrl(upload.thumbUrl());
        profile.setAvatarPreviewUrl(upload.previewUrl());
        profile.setAvatarVersion(profile.getAvatarVersion() + 1);
        profileRepository.save(profile);
        groupImSyncService.syncGroupFaceUrl(groupId, upload.thumbUrl());
        publishDisplay(groupId, userId, GroupRealtimePublisher.ACTION_GROUP_AVATAR_CHANGED);
        meGroupsListCache.invalidateGroup(groupId);
        return toView(requireMemberRow(groupId, userId), true);
    }

    public record ProfileUpdateRequest(String groupName, String notice) {}

    public record MyGroupsResponse(
        List<GroupProfileView> items,
        long total,
        int limit,
        int offset) {}

    private void ensureHydrated(String groupId, String userId) {
        Optional<GroupProfile> raw = projection.findProfileIncludingDismissed(groupId);
        if (raw.isPresent() && raw.get().isDismissed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        projection.hydrateGroupFromIm(groupId, userId);
        if (projection.isLocallyDismissed(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
    }

    private GroupProfile requireProfile(String groupId) {
        return projection.findProfile(groupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
    }

    private GroupMember requireMemberRow(String groupId, String userId) {
        return memberRepository.findByGroupIdAndUserIdActive(groupId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER"));
    }

    private GroupProfileView toView(GroupMember member, boolean detail) {
        boolean gameEnabled = groupGameService.isGameEnabled(member.getGroupId());
        return avatarDefaults.applyToView(projection.findProfile(member.getGroupId())
            .map(profile -> detail
                ? GroupProfileView.forDetail(profile, member, gameEnabled)
                : GroupProfileView.forList(profile, member, gameEnabled))
            .orElse(null));
    }

    private void publishDisplay(String groupId, String operatorUserId, String action) {
        groupChangeEmitter.emitDisplayInfoChanged(
            groupId, action, operatorUserId, Instant.now(), GroupChangeEventSource.REST_PROFILE);
    }

    private static ResponseStatusException mapImError(ImRestException e) {
        if ("IM_NOT_CONFIGURED".equals(e.getMessage())) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IM_NOT_CONFIGURED");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "IM_REST_ERROR");
    }
}
