package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.AddGroupMemberResult;
import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupJoinService {

    private static final Logger log = LoggerFactory.getLogger(GroupJoinService.class);

    public record JoinOptionsView(
        GroupJoinOption applyJoinOption,
        GroupJoinOption inviteJoinOption,
        boolean allowJoinByQrCode,
        boolean allowJoinByAlias) {}

    public record JoinOptionsUpdateRequest(
        GroupJoinOption applyJoinOption,
        GroupJoinOption inviteJoinOption,
        boolean allowJoinByQrCode,
        boolean allowJoinByAlias) {}

    public record JoinLookupView(
        String groupId,
        String groupType,
        String groupName,
        String displayAlias,
        String avatarUrl,
        int memberCount,
        String introduction,
        boolean channel) {}

    public record InviteMembersRequest(List<String> userIds, String message) {}

    public record InviteMemberResultItem(
        String userId,
        String status,
        Integer imResult,
        Long applicationId,
        String code) {}

    public record InviteMembersResponse(List<InviteMemberResultItem> results) {}

    public record JoinGroupRequest(String message, GroupJoinSource joinSource) {}

    public record JoinGroupResponse(String status, Long applicationId, Integer imResult, String code) {}

    public record JoinApplicationListResponse(List<JoinApplicationMapper.JoinApplicationItem> items) {}

    public record PendingInviteesResponse(List<String> userIds) {}

    public record MyJoinApplicationListResponse(
        List<JoinApplicationMapper.JoinApplicationItem> items,
        long total,
        int limit,
        int offset) {}

    public record HandleApplicationResponse(String status, Long applicationId, Integer imResult) {}

    public record DeleteApplicationsResponse(int deleted) {}

    private static final int DISMISS_CHUNK = 500;

    /** Redis miss 时同 key 并发合并，避免登录扇出惊群各打一遍 DB。 */
    private final ConcurrentHashMap<String, CompletableFuture<JoinApplicationListResponse>> listLoadInflight =
        new ConcurrentHashMap<>();

    private final GroupSettingsRepository settingsRepository;
    private final GroupJoinApplicationRepository applicationRepository;
    private final GroupJoinApplicationDismissRepository applicationDismissRepository;
    private final GroupProfileRepository profileRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessService access;
    private final ImAdminClient im;
    private final UserFriendService friendService;
    private final UserRepository userRepository;
    private final GroupRealtimePublisher groupRealtime;
    private final GroupProjectionService groupProjection;
    private final GroupMemberEnrichmentService enrichment;
    private final GroupAvatarDefaults avatarDefaults;
    private final GroupChangeEmitter groupChangeEmitter;
    private final JoinApplicationsListCache joinApplicationsListCache;
    private final GroupJoinLimitService joinLimitService;
    private final ImGroupIdRemapLookup groupIdRemapLookup;
    private final GroupFanoutTargetResolver fanoutTargets;
    private final GroupImSyncService groupImSyncService;
    private final boolean imPortraitEnabled;

    public GroupJoinService(GroupSettingsRepository settingsRepository,
                            GroupJoinApplicationRepository applicationRepository,
                            GroupJoinApplicationDismissRepository applicationDismissRepository,
                            GroupProfileRepository profileRepository,
                            GroupMemberRepository memberRepository,
                            GroupAccessService access,
                            ImAdminClient im,
                            UserFriendService friendService,
                            UserRepository userRepository,
                            GroupRealtimePublisher groupRealtime,
                            GroupProjectionService groupProjection,
                            GroupMemberEnrichmentService enrichment,
                            GroupAvatarDefaults avatarDefaults,
                            GroupChangeEmitter groupChangeEmitter,
                            JoinApplicationsListCache joinApplicationsListCache,
                            GroupJoinLimitService joinLimitService,
                            ImGroupIdRemapLookup groupIdRemapLookup,
                            GroupFanoutTargetResolver fanoutTargets,
                            GroupImSyncService groupImSyncService,
                            @Value("${chat99.group.join-apps-im-portrait-enabled:false}") boolean imPortraitEnabled) {
        this.settingsRepository = settingsRepository;
        this.applicationRepository = applicationRepository;
        this.applicationDismissRepository = applicationDismissRepository;
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
        this.access = access;
        this.im = im;
        this.friendService = friendService;
        this.userRepository = userRepository;
        this.groupRealtime = groupRealtime;
        this.groupProjection = groupProjection;
        this.enrichment = enrichment;
        this.avatarDefaults = avatarDefaults;
        this.groupChangeEmitter = groupChangeEmitter;
        this.joinApplicationsListCache = joinApplicationsListCache;
        this.joinLimitService = joinLimitService;
        this.groupIdRemapLookup = groupIdRemapLookup;
        this.fanoutTargets = fanoutTargets;
        this.groupImSyncService = groupImSyncService;
        this.imPortraitEnabled = imPortraitEnabled;
    }

    public JoinOptionsView getJoinOptions(String groupId, String callerUserId) {
        access.requireBackendInviteGroup(groupId);
        access.requireMember(groupId, callerUserId);
        GroupSettings settings = resolveSettings(groupId);
        if (groupProjection.isChannel(groupId)) {
            return new JoinOptionsView(GroupJoinOption.free_access,
                settings.getInviteJoinOption(), true, true);
        }
        return toJoinOptionsView(settings);
    }

    @Transactional
    public JoinOptionsView updateJoinOptions(String groupId, String callerUserId, JoinOptionsUpdateRequest req) {
        access.requireBackendInviteGroup(groupId);
        access.requireAdminRole(groupId, callerUserId);
        GroupSettings row = loadOrCreateSettings(groupId);
        boolean channel = groupProjection.isChannel(groupId);
        GroupJoinOption applyOption = channel
            ? GroupJoinOption.free_access : req.applyJoinOption();
        row.setApplyJoinOption(applyOption);
        row.setInviteJoinOption(req.inviteJoinOption());
        row.setAllowJoinByQrCode(channel || req.allowJoinByQrCode());
        row.setAllowJoinByAlias(channel || req.allowJoinByAlias());
        settingsRepository.save(row);
        groupImSyncService.syncJoinOptions(groupId, applyOption, req.inviteJoinOption());
        notifyJoinOptionChanged(groupId, callerUserId, row);
        return toJoinOptionsView(row);
    }

    public JoinLookupView joinLookup(String callerUserId, String keyword, GroupJoinSource joinSource) {
        requireActiveUser(callerUserId);
        if (keyword == null || keyword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        GroupProfile profile = resolveProfileByKeyword(keyword.trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
        if (!GroupAccessService.BACKEND_INVITE_GROUP_TYPES.contains(profile.getGroupType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        GroupSettings settings = resolveSettings(profile.getGroupId());
        if (!profile.isChannel()) {
            assertJoinEntryAllowed(settings, joinSource);
        }
        return toJoinLookupView(profile);
    }

    @Transactional
    public InviteMembersResponse inviteMembers(String groupId, String callerUserId, InviteMembersRequest req) {
        access.requireBackendInviteGroup(groupId);
        String role = access.requireMemberRole(groupId, callerUserId);
        boolean admin = access.isAdminRole(role);
        GroupSettings settings = resolveSettings(groupId);
        List<String> userIds = normalizeUserIds(req.userIds());
        if (userIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (userIds.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }

        String groupType = requireGroupType(groupId);
        List<String> alreadyMembers = userIds.stream()
            .filter(uid -> access.isMember(groupId, uid))
            .toList();
        joinLimitService.assertUsersCanJoin(userIds, groupType, alreadyMembers);

        List<InviteMemberResultItem> results = new ArrayList<>();
        List<String> directAdd = new ArrayList<>();
        for (String peerId : userIds) {
            InviteMemberResultItem precheck = precheckInviteTarget(groupId, callerUserId, peerId);
            if (precheck != null) {
                results.add(precheck);
                continue;
            }
            if (admin) {
                directAdd.add(peerId);
                continue;
            }
            if (settings.getInviteJoinOption() == GroupJoinOption.disabled) {
                results.add(failed(peerId, "INVITE_DISABLED"));
                continue;
            }
            if (settings.getInviteJoinOption() == GroupJoinOption.free_access) {
                directAdd.add(peerId);
                continue;
            }
            results.add(createInvitePending(groupId, callerUserId, peerId, req.message()));
        }

        if (!directAdd.isEmpty()) {
            results.addAll(addMembersDirect(
                groupId, callerUserId, directAdd, GroupChangeEventSource.REST_INVITE,
                callerUserId, GroupMemberJoinChannel.INVITE));
        }
        return new InviteMembersResponse(results);
    }

    @Transactional
    public JoinGroupResponse applyJoin(String groupId, String callerUserId, JoinGroupRequest req) {
        access.requireBackendInviteGroup(groupId);
        if (access.isMember(groupId, callerUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_GROUP_MEMBER");
        }
        requireActiveUser(callerUserId);
        String groupType = requireGroupType(groupId);
        joinLimitService.assertUsersCanJoin(List.of(callerUserId), groupType, List.of());
        GroupSettings settings = resolveSettings(groupId);
        GroupJoinSource joinSource = req == null ? null : req.joinSource();
        if (!groupProjection.isChannel(groupId)) {
            assertJoinEntryAllowed(settings, joinSource);
        }
        boolean channel = groupProjection.isChannel(groupId);
        if (!channel && settings.getApplyJoinOption() == GroupJoinOption.disabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "JOIN_DISABLED");
        }
        if (channel || settings.getApplyJoinOption() == GroupJoinOption.free_access) {
            List<InviteMemberResultItem> added = addMembersDirect(
                groupId, callerUserId, List.of(callerUserId),
                channel ? null : GroupChangeEventSource.REST_JOIN,
                null, GroupMemberJoinChannel.GROUP_ID);
            InviteMemberResultItem item = added.isEmpty()
                ? failed(callerUserId, "ADD_FAILED")
                : added.get(0);
            return new JoinGroupResponse(item.status(), null, item.imResult(), item.code());
        }
        if (applicationRepository.existsByGroupIdAndTypeAndFromUserIdAndToUserIdAndStatus(
            groupId, GroupJoinApplicationType.apply, callerUserId, callerUserId,
            GroupJoinApplicationStatus.pending)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "APPLICATION_PENDING");
        }
        GroupJoinApplication app = new GroupJoinApplication();
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.apply);
        app.setFromUserId(callerUserId);
        app.setToUserId(callerUserId);
        app.setMessage(trimMessage(req == null ? null : req.message()));
        app.setJoinSource(joinSource);
        applicationRepository.save(app);
        notifyApplicationPending(groupId, app);
        joinApplicationsListCache.invalidateGroup(groupId);
        return new JoinGroupResponse("pending", app.getId(), null, null);
    }

    public JoinApplicationListResponse listApplications(String groupId, String callerUserId,
                                                        boolean includeHandled) {
        requireAdminForJoinList(groupId, callerUserId);
        Optional<JoinApplicationListResponse> cached =
            joinApplicationsListCache.get(groupId, callerUserId, includeHandled);
        if (cached.isPresent()) {
            return cached.get();
        }
        String coalesceKey = groupId + '\0' + callerUserId + '\0' + includeHandled;
        CompletableFuture<JoinApplicationListResponse> created = new CompletableFuture<>();
        CompletableFuture<JoinApplicationListResponse> existing =
            listLoadInflight.putIfAbsent(coalesceKey, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            JoinApplicationListResponse response = loadApplicationsUncached(groupId, callerUserId, includeHandled);
            created.complete(response);
            return response;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            listLoadInflight.remove(coalesceKey, created);
        }
    }

    private JoinApplicationListResponse loadApplicationsUncached(String groupId,
                                                                 String callerUserId,
                                                                 boolean includeHandled) {
        List<GroupJoinApplication> apps = includeHandled
            ? applicationRepository.findByGroupIdExcludingDismissed(groupId, callerUserId)
            : applicationRepository.findByGroupIdAndStatusExcludingDismissed(
                groupId, GroupJoinApplicationStatus.pending, callerUserId);
        JoinApplicationListResponse response = new JoinApplicationListResponse(toItems(apps, callerUserId));
        joinApplicationsListCache.put(groupId, callerUserId, includeHandled, response);
        return response;
    }

    /** 待审被邀请人 ID（type=invite + pending）；群成员可读。 */
    public PendingInviteesResponse listPendingInvitees(String groupId, String callerUserId) {
        access.requireBackendInviteGroup(groupId);
        access.requireMember(groupId, callerUserId);
        return new PendingInviteesResponse(applicationRepository.findPendingInviteeUserIds(groupId));
    }

    public MyJoinApplicationListResponse listMyApplications(String userId, int limit, int offset,
                                                              boolean includeHandled, String statusFilter) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        GroupJoinApplicationStatus status = JoinApplicationMapper.parseStatusFilter(statusFilter);
        if (!includeHandled && status == null) {
            status = GroupJoinApplicationStatus.pending;
        }
        var page = applicationRepository.findMyApplications(
            userId, status, org.springframework.data.domain.PageRequest.of(
                safeOffset / safeLimit, safeLimit));
        return new MyJoinApplicationListResponse(
            toItems(page.getContent(), userId),
            page.getTotalElements(),
            safeLimit,
            safeOffset);
    }

    @Transactional
    public HandleApplicationResponse approveApplication(String groupId, String callerUserId, long applicationId) {
        return handleApplication(groupId, callerUserId, applicationId, true);
    }

    @Transactional
    public HandleApplicationResponse rejectApplication(String groupId, String callerUserId, long applicationId) {
        return handleApplication(groupId, callerUserId, applicationId, false);
    }

    @Transactional
    public DeleteApplicationsResponse deleteApplication(String groupId, String callerUserId, long applicationId) {
        return deleteApplications(groupId, callerUserId, List.of(applicationId), null, false);
    }

    @Transactional
    public DeleteApplicationsResponse deleteApplications(String groupId,
                                                         String callerUserId,
                                                         List<Long> applicationIds,
                                                         String statusFilter,
                                                         boolean includePendingWhenAll) {
        access.requireBackendInviteGroup(groupId);
        access.requireAdminRole(groupId, callerUserId);
        List<Long> ids = normalizeApplicationIds(applicationIds);
        if (!ids.isEmpty()) {
            int deleted = applicationRepository.deleteByGroupIdAndIdIn(groupId, ids);
            if (deleted == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND");
            }
            joinApplicationsListCache.invalidateGroup(groupId);
            return new DeleteApplicationsResponse(deleted);
        }
        int deleted = deleteAllInGroup(groupId, statusFilter, includePendingWhenAll);
        if (deleted > 0) {
            joinApplicationsListCache.invalidateGroup(groupId);
        }
        return new DeleteApplicationsResponse(deleted);
    }

    @Transactional
    public DeleteApplicationsResponse deleteMyApplication(String userId, long applicationId) {
        return deleteMyApplications(userId, List.of(applicationId), null);
    }

    @Transactional
    public DeleteApplicationsResponse deleteMyApplications(String userId,
                                                           List<Long> applicationIds,
                                                           String statusFilter) {
        List<Long> ids = normalizeApplicationIds(applicationIds);
        GroupJoinApplicationStatus status = JoinApplicationMapper.parseStatusFilter(statusFilter);
        boolean byIds = !ids.isEmpty();
        List<GroupJoinApplication> candidates;
        if (byIds) {
            candidates = resolveVisibleApplications(userId, ids, status);
        } else {
            candidates = loadApplicationsByIds(
                applicationRepository.findVisibleUndismissedApplicationIds(userId, status));
        }
        List<Long> candidateIds = candidates.stream().map(GroupJoinApplication::getId).toList();
        List<Long> toInsert = subtractExistingDismissals(userId, candidateIds);
        if (byIds && toInsert.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND");
        }
        if (!toInsert.isEmpty()) {
            applicationDismissRepository.insertIgnoreBatch(userId, toInsert, Instant.now());
            Set<Long> inserted = new LinkedHashSet<>(toInsert);
            Set<String> groupIds = new LinkedHashSet<>();
            for (GroupJoinApplication app : candidates) {
                if (inserted.contains(app.getId())
                    && app.getGroupId() != null
                    && !app.getGroupId().isBlank()) {
                    groupIds.add(app.getGroupId());
                }
            }
            for (String groupId : groupIds) {
                joinApplicationsListCache.invalidateGroup(groupId);
            }
        }
        return new DeleteApplicationsResponse(toInsert.size());
    }

    private List<GroupJoinApplication> resolveVisibleApplications(
        String userId,
        List<Long> applicationIds,
        GroupJoinApplicationStatus statusFilter) {
        List<GroupJoinApplication> visible = new ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (int from = 0; from < applicationIds.size(); from += DISMISS_CHUNK) {
            int to = Math.min(from + DISMISS_CHUNK, applicationIds.size());
            List<Long> chunk = applicationIds.subList(from, to);
            for (GroupJoinApplication app : applicationRepository.findAllById(chunk)) {
                if (app == null || !seen.add(app.getId())) {
                    continue;
                }
                if (!canViewInMyApplications(userId, app)) {
                    continue;
                }
                if (statusFilter != null && app.getStatus() != statusFilter) {
                    continue;
                }
                visible.add(app);
            }
        }
        return visible;
    }

    private List<GroupJoinApplication> loadApplicationsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, GroupJoinApplication> byId = new HashMap<>();
        for (int from = 0; from < ids.size(); from += DISMISS_CHUNK) {
            int to = Math.min(from + DISMISS_CHUNK, ids.size());
            for (GroupJoinApplication app : applicationRepository.findAllById(ids.subList(from, to))) {
                if (app != null) {
                    byId.put(app.getId(), app);
                }
            }
        }
        List<GroupJoinApplication> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            GroupJoinApplication app = byId.get(id);
            if (app != null) {
                ordered.add(app);
            }
        }
        return ordered;
    }

    private List<Long> subtractExistingDismissals(String userId, List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> toInsert = new LinkedHashSet<>(candidateIds);
        for (int from = 0; from < candidateIds.size(); from += DISMISS_CHUNK) {
            int to = Math.min(from + DISMISS_CHUNK, candidateIds.size());
            List<Long> chunk = candidateIds.subList(from, to);
            toInsert.removeAll(
                applicationDismissRepository.findApplicationIdsByUserIdAndApplicationIdIn(userId, chunk));
        }
        return new ArrayList<>(toInsert);
    }

    /** 单条隐藏（邀请人自动隐藏等）；已隐藏或不可见返回 false，不抛 404。 */
    private boolean dismissApplication(String userId, Long applicationId) {
        if (applicationId == null) {
            return false;
        }
        GroupJoinApplication app = applicationRepository.findById(applicationId).orElse(null);
        if (app == null || !canViewInMyApplications(userId, app)) {
            return false;
        }
        List<Long> toInsert = subtractExistingDismissals(userId, List.of(applicationId));
        if (toInsert.isEmpty()) {
            return false;
        }
        applicationDismissRepository.insertIgnoreBatch(userId, toInsert, Instant.now());
        if (app.getGroupId() != null && !app.getGroupId().isBlank()) {
            joinApplicationsListCache.invalidateGroup(app.getGroupId());
        }
        return true;
    }

    private boolean canViewInMyApplications(String userId, GroupJoinApplication app) {
        if (app.getType() == GroupJoinApplicationType.apply && userId.equals(app.getFromUserId())) {
            return true;
        }
        if (app.getType() == GroupJoinApplicationType.invite
            && (userId.equals(app.getToUserId()) || userId.equals(app.getFromUserId()))) {
            return true;
        }
        return groupProjection.findMember(app.getGroupId(), userId)
            .map(GroupMember::getRole)
            .filter(role -> role >= GroupRoleCodec.ADMIN)
            .isPresent();
    }

    private HandleApplicationResponse handleApplication(String groupId, String callerUserId, long applicationId,
                                                      boolean approve) {
        access.requireBackendInviteGroup(groupId);
        access.requireAdminRole(groupId, callerUserId);
        GroupJoinApplication app = applicationRepository.findByIdAndGroupId(applicationId, groupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
        if (app.getStatus() != GroupJoinApplicationStatus.pending) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "APPLICATION_ALREADY_HANDLED");
        }
        Integer imResult = null;
        if (approve) {
            long t0 = System.nanoTime();
            String targetUserId = app.getType() == GroupJoinApplicationType.invite
                ? app.getToUserId()
                : app.getFromUserId();
            String groupType = requireGroupType(groupId);
            if (!access.isMember(groupId, targetUserId)) {
                joinLimitService.assertUsersCanJoin(List.of(targetUserId), groupType, List.of());
            }
            // 不走 addMembersDirect：emit 与 notify 一并放到 afterCommit，并打分段耗时
            AddMembersCoreResult core = addMembersCore(
                groupId, List.of(targetUserId),
                app.getType() == GroupJoinApplicationType.invite ? app.getFromUserId() : null,
                app.getType() == GroupJoinApplicationType.invite
                    ? GroupMemberJoinChannel.INVITE
                    : GroupMemberJoinChannel.GROUP_ID);
            List<InviteMemberResultItem> added = core.results();
            if (added.isEmpty() || !"added".equals(added.get(0).status())
                && !"already_member".equals(added.get(0).status())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ADD_MEMBER_FAILED");
            }
            imResult = added.get(0).imResult();
            app.setStatus(GroupJoinApplicationStatus.approved);
            app.setHandledBy(callerUserId);
            app.setHandledAt(Instant.now());
            applicationRepository.save(app);
            final long imAddMs = core.imAddMs();
            final long projectionMs = core.projectionMs();
            final List<String> addedUserIds = core.addedUserIds();
            final long appId = app.getId();
            runAfterCommit(() -> {
                long emitMs = 0L;
                long notifyMs = 0L;
                int targetCount = 0;
                long tEmit = System.nanoTime();
                try {
                    targetCount = emitMemberAddedFromRest(
                        groupId, callerUserId, addedUserIds, GroupChangeEventSource.REST_APPROVE);
                } catch (Exception e) {
                    log.warn("join_application_approve emit failed groupId={} applicationId={}",
                        groupId, appId, e);
                }
                emitMs = elapsedMs(tEmit);
                long tNotify = System.nanoTime();
                try {
                    notifyApplicationHandled(groupId, app, true);
                } catch (Exception e) {
                    log.warn("join_application_approve notify failed groupId={} applicationId={}",
                        groupId, appId, e);
                }
                notifyMs = elapsedMs(tNotify);
                log.info(
                    "join_application_approve timing groupId={} applicationId={} imAddMs={} projectionMs={} "
                        + "emitMs={} notifyMs={} totalMs={} targetCount={}",
                    groupId, appId, imAddMs, projectionMs, emitMs, notifyMs, elapsedMs(t0), targetCount);
            });
            joinApplicationsListCache.invalidateGroup(groupId);
            return new HandleApplicationResponse(app.getStatus().name(), app.getId(), imResult);
        }
        app.setStatus(GroupJoinApplicationStatus.rejected);
        app.setHandledBy(callerUserId);
        app.setHandledAt(Instant.now());
        applicationRepository.save(app);
        final long appId = app.getId();
        runAfterCommit(() -> {
            try {
                notifyApplicationHandled(groupId, app, false);
            } catch (Exception e) {
                log.warn("join_application_reject notify failed groupId={} applicationId={}",
                    groupId, appId, e);
            }
        });
        joinApplicationsListCache.invalidateGroup(groupId);
        return new HandleApplicationResponse(app.getStatus().name(), app.getId(), imResult);
    }

    private InviteMemberResultItem precheckInviteTarget(String groupId, String callerUserId, String peerId) {
        if (callerUserId.equals(peerId)) {
            return failed(peerId, "INVALID_INPUT");
        }
        try {
            requireUserExists(peerId);
        } catch (ResponseStatusException e) {
            return failed(peerId, "USER_NOT_FOUND");
        }
        if (!friendService.isMutualActive(callerUserId, peerId)) {
            return failed(peerId, "NOT_FRIEND");
        }
        if (access.isMember(groupId, peerId)) {
            return new InviteMemberResultItem(peerId, "already_member", 2, null, null);
        }
        return null;
    }

    private InviteMemberResultItem createInvitePending(String groupId, String callerUserId, String peerId,
                                                     String message) {
        if (applicationRepository.existsByGroupIdAndTypeAndFromUserIdAndToUserIdAndStatus(
            groupId, GroupJoinApplicationType.invite, callerUserId, peerId, GroupJoinApplicationStatus.pending)) {
            return failed(peerId, "APPLICATION_PENDING");
        }
        GroupJoinApplication app = new GroupJoinApplication();
        app.setGroupId(groupId);
        app.setType(GroupJoinApplicationType.invite);
        app.setFromUserId(callerUserId);
        app.setToUserId(peerId);
        app.setMessage(trimMessage(message));
        applicationRepository.save(app);
        dismissApplication(callerUserId, app.getId());
        notifyApplicationPending(groupId, app);
        joinApplicationsListCache.invalidateGroup(groupId);
        return new InviteMemberResultItem(peerId, "pending", 3, app.getId(), null);
    }

    private record AddMembersCoreResult(
        List<InviteMemberResultItem> results,
        List<String> addedUserIds,
        long imAddMs,
        long projectionMs) {}

    private List<InviteMemberResultItem> addMembersDirect(String groupId, String operatorUserId,
                                                          List<String> userIds, String changeSource,
                                                          String invitedBy, String joinChannel) {
        AddMembersCoreResult core = addMembersCore(groupId, userIds, invitedBy, joinChannel);
        if (!core.addedUserIds().isEmpty()) {
            final List<String> added = core.addedUserIds();
            runAfterCommit(() -> {
                try {
                    emitMemberAddedFromRest(groupId, operatorUserId, added, changeSource);
                } catch (Exception e) {
                    log.warn("emitMemberAdded afterCommit failed groupId={} source={} addedCount={}",
                        groupId, changeSource, added.size(), e);
                }
            });
        }
        return core.results();
    }

    /**
     * 本地投影加成员；IM 异步同步（{@link GroupImSyncService}）。不含 realtime emit。
     */
    private AddMembersCoreResult addMembersCore(String groupId,
                                                List<String> userIds,
                                                String invitedBy,
                                                String joinChannel) {
        if (userIds != null && !userIds.isEmpty()) {
            String groupType = requireGroupType(groupId);
            List<String> already = userIds.stream()
                .filter(uid -> access.isMember(groupId, uid))
                .toList();
            joinLimitService.assertUsersCanJoin(userIds, groupType, already);
        }
        long tIm = System.nanoTime();
        List<InviteMemberResultItem> out = new ArrayList<>();
        List<String> toProject = new ArrayList<>();
        if (userIds != null) {
            for (String uid : userIds) {
                if (uid == null || uid.isBlank()) {
                    continue;
                }
                String trimmed = uid.trim();
                if (access.isMember(groupId, trimmed)) {
                    out.add(new InviteMemberResultItem(trimmed, "already_member", 2, null, null));
                } else {
                    toProject.add(trimmed);
                    // imResult=1：与旧「同步 IM Result=1」对齐，避免线上客户端把 0 误判为失败
                    out.add(new InviteMemberResultItem(trimmed, "added", 1, null, null));
                }
            }
        }
        long imAddMs = elapsedMs(tIm);
        List<String> added = List.of();
        long projectionMs = 0L;
        if (!toProject.isEmpty()) {
            long tProj = System.nanoTime();
            groupProjection.onMembersJoined(groupId, toProject, invitedBy, joinChannel);
            projectionMs = elapsedMs(tProj);
            added = List.copyOf(toProject);
            groupImSyncService.syncAddMembers(groupId, toProject);
        }
        return new AddMembersCoreResult(out, added, imAddMs, projectionMs);
    }

    /**
     * 推送 member_added：优先本地投影成员列表，空再 fallback IM 全员拉取。
     *
     * @return fanout target 数量（含并入的 addedUserIds）
     */
    private int emitMemberAddedFromRest(String groupId,
                                        String operatorUserId,
                                        List<String> addedUserIds,
                                        String changeSource) {
        if (changeSource == null || changeSource.isBlank() || addedUserIds == null || addedUserIds.isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(fanoutTargets.resolveMemberTargets(groupId));
        for (String userId : addedUserIds) {
            if (userId != null && !userId.isBlank()) {
                targetSet.add(userId);
            }
        }
        List<String> targets = new ArrayList<>(targetSet);
        groupChangeEmitter.emitMemberAdded(
            groupId,
            operatorUserId,
            addedUserIds,
            targets,
            Instant.now(),
            changeSource);
        return targets.size();
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static InviteMemberResultItem mapImAddResult(String userId, AddGroupMemberResult row) {
        return switch (row.result()) {
            case 1 -> new InviteMemberResultItem(userId, "added", 1, null, null);
            case 2 -> new InviteMemberResultItem(userId, "already_member", 2, null, null);
            case 3 -> new InviteMemberResultItem(userId, "pending", 3, null, null);
            default -> failed(userId, row.resultInfo() == null ? "ADD_FAILED" : row.resultInfo());
        };
    }

    private static InviteMemberResultItem failed(String userId, String code) {
        return new InviteMemberResultItem(userId, "failed", 0, null, code);
    }

    private GroupSettings resolveSettings(String groupId) {
        return settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings defaults = new GroupSettings();
            defaults.setGroupId(groupId);
            im.fetchGroupAdminInfo(groupId).ifPresent(info -> {
                defaults.setApplyJoinOption(GroupJoinOption.fromImApply(info.applyJoinOption()));
                defaults.setInviteJoinOption(GroupJoinOption.fromImInvite(info.inviteJoinOption()));
            });
            return defaults;
        });
    }

    private GroupSettings loadOrCreateSettings(String groupId) {
        return settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings created = new GroupSettings();
            created.setGroupId(groupId);
            return created;
        });
    }

    private static JoinOptionsView toJoinOptionsView(GroupSettings row) {
        return new JoinOptionsView(
            row.getApplyJoinOption(),
            row.getInviteJoinOption(),
            row.isAllowJoinByQrCode(),
            row.isAllowJoinByAlias());
    }

    private JoinLookupView toJoinLookupView(GroupProfile profile) {
        String displayAlias = profile.getDisplayAlias();
        if (displayAlias == null || displayAlias.isBlank()) {
            displayAlias = GroupDisplayAliasUtil.compute(profile.getGroupType(), profile.getGroupId());
        }
        return new JoinLookupView(
            profile.getGroupId(),
            profile.getGroupType(),
            profile.getGroupName(),
            displayAlias,
            avatarDefaults.resolve(profile.getAvatarUrl()),
            profile.getMemberCount(),
            "",
            profile.isChannel());
    }

    private java.util.Optional<GroupProfile> resolveProfileByKeyword(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            java.util.Optional<String> remapped = groupIdRemapLookup.findDstBySrc(keyword.trim());
            if (remapped.isPresent()) {
                java.util.Optional<GroupProfile> mapped = groupProjection.findProfile(remapped.get());
                if (mapped.isPresent()) {
                    return mapped;
                }
            }
        }
        for (String groupId : GroupJoinKeywordResolver.candidateGroupIds(keyword)) {
            java.util.Optional<String> remapped = groupIdRemapLookup.findDstBySrc(groupId);
            if (remapped.isPresent()) {
                java.util.Optional<GroupProfile> mapped = groupProjection.findProfile(remapped.get());
                if (mapped.isPresent()) {
                    return mapped;
                }
            }
            java.util.Optional<GroupProfile> profile = groupProjection.findProfile(groupId);
            if (profile.isPresent()) {
                return profile;
            }
        }
        for (String groupId : GroupJoinKeywordResolver.candidateGroupIds(keyword)) {
            java.util.Optional<GroupAdminInfo> info = im.fetchGroupAdminInfo(groupId);
            if (info.isEmpty()) {
                continue;
            }
            String type = info.get().type();
            if (type == null || !GroupAccessService.BACKEND_INVITE_GROUP_TYPES.contains(type)) {
                continue;
            }
            java.util.Optional<GroupProfile> profile = groupProjection.findProfile(groupId);
            if (profile.isPresent()) {
                return profile;
            }
            GroupProfile fromIm = new GroupProfile();
            fromIm.setGroupId(info.get().groupId());
            fromIm.setGroupType(type);
            fromIm.setGroupName(info.get().name() == null ? "" : info.get().name());
            fromIm.setDisplayAlias(GroupDisplayAliasUtil.compute(type, info.get().groupId()));
            fromIm.setAvatarUrl(info.get().faceUrl());
            fromIm.setMemberCount(info.get().memberNum() == null ? 0 : info.get().memberNum());
            return java.util.Optional.of(fromIm);
        }
        return java.util.Optional.empty();
    }

    private static void assertJoinEntryAllowed(GroupSettings settings, GroupJoinSource joinSource) {
        if (joinSource == null) {
            return;
        }
        if (joinSource == GroupJoinSource.qr_code && !settings.isAllowJoinByQrCode()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "JOIN_BY_QR_DISABLED");
        }
        if ((joinSource == GroupJoinSource.group_alias || joinSource == GroupJoinSource.search)
            && !settings.isAllowJoinByAlias()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "JOIN_BY_ALIAS_DISABLED");
        }
    }

    private List<JoinApplicationMapper.JoinApplicationItem> toItems(List<GroupJoinApplication> apps,
                                                                    String viewerUserId) {
        if (apps == null || apps.isEmpty()) {
            return List.of();
        }
        Map<String, ImAdminClient.ProfilePortrait> profiles = imPortraitEnabled
            ? loadUserProfiles(apps)
            : Map.of();
        Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs = loadLocalBriefs(apps);
        Map<String, GroupProfile> profilesByGroup = loadGroupProfiles(apps);
        Map<String, Integer> rolesByGroup = loadViewerRolesInGroups(apps, viewerUserId);
        return apps.stream()
            .map(app -> {
                GroupProfile profile = profilesByGroup.get(app.getGroupId());
                String avatarUrl = profile == null
                    ? null
                    : avatarDefaults.resolve(profile.getAvatarUrl());
                return JoinApplicationMapper.toItem(
                    app, profiles, localBriefs, profile, avatarUrl,
                    viewerUserId, rolesByGroup.get(app.getGroupId()));
            })
            .toList();
    }

    /**
     * 热路径优先本地投影鉴权，避免 requireGroupInfo + requireMember 各查一遍。
     * 本地 miss 时回退 {@link GroupAccessService}（含 IM fallback / hydrate 入队）。
     */
    private void requireAdminForJoinList(String groupId, String callerUserId) {
        Optional<GroupProfile> profile = groupProjection.findProfile(groupId);
        if (profile.isPresent()) {
            String type = profile.get().getGroupType();
            if (type == null || !GroupAccessService.BACKEND_INVITE_GROUP_TYPES.contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_TYPE_NOT_SUPPORTED");
            }
            Optional<GroupMember> member = groupProjection.findMember(groupId, callerUserId);
            if (member.isPresent()) {
                int role = member.get().getRole();
                if (role < GroupRoleCodec.ADMIN) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_ADMIN");
                }
                return;
            }
        }
        access.requireBackendInviteGroup(groupId);
        access.requireAdminRole(groupId, callerUserId);
    }

    private Map<String, Integer> loadViewerRolesInGroups(List<GroupJoinApplication> apps, String viewerUserId) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return Map.of();
        }
        Set<String> groupIds = new LinkedHashSet<>();
        for (GroupJoinApplication app : apps) {
            if (app.getGroupId() != null && !app.getGroupId().isBlank()) {
                groupIds.add(app.getGroupId());
            }
        }
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new HashMap<>();
        for (GroupMember member : memberRepository.findByUserIdAndGroupIdIn(viewerUserId, groupIds)) {
            out.put(member.getGroupId(), member.getRole());
        }
        return out;
    }

    private Map<String, GroupProfile> loadGroupProfiles(List<GroupJoinApplication> apps) {
        Set<String> groupIds = new LinkedHashSet<>();
        for (GroupJoinApplication app : apps) {
            if (app.getGroupId() != null && !app.getGroupId().isBlank()) {
                groupIds.add(app.getGroupId());
            }
        }
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<String, GroupProfile> out = new HashMap<>();
        for (GroupProfile profile : profileRepository.findAllById(groupIds)) {
            if (!profile.isDismissed()) {
                out.put(profile.getGroupId(), profile);
            }
        }
        return out;
    }

    private Map<String, GroupMemberEnrichmentService.UserBrief> loadLocalBriefs(
        List<GroupJoinApplication> apps) {
        Set<String> userIds = new LinkedHashSet<>();
        for (GroupJoinApplication app : apps) {
            if (app.getFromUserId() != null) {
                userIds.add(app.getFromUserId());
            }
            if (app.getToUserId() != null) {
                userIds.add(app.getToUserId());
            }
            if (app.getHandledBy() != null) {
                userIds.add(app.getHandledBy());
            }
        }
        return enrichment.loadUserBriefs(userIds);
    }

    private Map<String, ImAdminClient.ProfilePortrait> loadUserProfiles(List<GroupJoinApplication> apps) {
        if (apps == null || apps.isEmpty()) {
            return Map.of();
        }
        Set<String> userIds = new LinkedHashSet<>();
        for (GroupJoinApplication app : apps) {
            if (app.getFromUserId() != null && !app.getFromUserId().isBlank()) {
                userIds.add(app.getFromUserId().trim());
            }
            if (app.getToUserId() != null && !app.getToUserId().isBlank()) {
                userIds.add(app.getToUserId().trim());
            }
            if (app.getHandledBy() != null && !app.getHandledBy().isBlank()) {
                userIds.add(app.getHandledBy().trim());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            return im.getPortraitProfiles(userIds);
        } catch (Exception e) {
            return Map.of();
        }
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

    private String requireGroupType(String groupId) {
        return profileRepository.findById(groupId)
            .map(GroupProfile::getGroupType)
            .filter(t -> t != null && !t.isBlank())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
    }

    private static List<Long> normalizeApplicationIds(List<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return List.of();
        }
        return applicationIds.stream()
            .filter(Objects::nonNull)
            .filter(id -> id > 0)
            .distinct()
            .toList();
    }

    private int deleteAllInGroup(String groupId, String statusFilter, boolean includePendingWhenAll) {
        GroupJoinApplicationStatus status = JoinApplicationMapper.parseStatusFilter(statusFilter);
        if (status != null) {
            return applicationRepository.deleteByGroupIdAndOptionalStatus(groupId, status);
        }
        if (includePendingWhenAll) {
            return applicationRepository.deleteByGroupIdAndOptionalStatus(groupId, null);
        }
        return applicationRepository.deleteByGroupIdAndStatusIn(
            groupId,
            List.of(GroupJoinApplicationStatus.approved, GroupJoinApplicationStatus.rejected));
    }

    private static List<String> normalizeUserIds(List<String> userIds) {
        if (userIds == null) {
            return List.of();
        }
        return userIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    }

    private static String trimMessage(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 256 ? trimmed.substring(0, 256) : trimmed;
    }

    private void notifyJoinOptionChanged(String groupId, String operatorUserId, GroupSettings settings) {
        List<String> targets = fanoutTargets.resolveMemberTargets(groupId);
        if (targets.isEmpty()) {
            return;
        }
        publishRealtime(
            groupId,
            GroupRealtimePublisher.ACTION_GROUP_JOIN_OPTION_CHANGED,
            operatorUserId,
            List.of(),
            targets,
            Map.of(
                "applyJoinOption", settings.getApplyJoinOption().name(),
                "inviteJoinOption", settings.getInviteJoinOption().name(),
                "allowJoinByQrCode", settings.isAllowJoinByQrCode(),
                "allowJoinByAlias", settings.isAllowJoinByAlias()));
    }

    private void notifyApplicationPending(String groupId, GroupJoinApplication app) {
        List<String> admins = listAdminUserIds(groupId);
        if (admins.isEmpty()) {
            return;
        }
        Map<String, ImAdminClient.ProfilePortrait> profiles = loadUserProfiles(List.of(app));
        Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs = loadLocalBriefs(List.of(app));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("applicationType", JoinApplicationMapper.toApiApplicationType(app.getType()));
        detail.put("fromUserId", app.getFromUserId());
        if (app.getType() == GroupJoinApplicationType.invite) {
            detail.put("toUserId", app.getToUserId());
        }
        if (app.getMessage() != null) {
            detail.put("message", app.getMessage());
        }
        detail.put("status", app.getStatus().name());
        if (app.getCreatedAt() != null) {
            detail.put("createdAt", app.getCreatedAt().toEpochMilli());
            detail.put("updatedAt", app.getCreatedAt().toEpochMilli());
        } else {
            detail.put("updatedAt", System.currentTimeMillis());
        }
        String fromNick = JoinApplicationMapper.toItem(
            app, profiles, localBriefs, null, null).fromUserNickName();
        if (fromNick != null) {
            detail.put("fromUserNickName", fromNick);
        }
        publishRealtime(
            groupId,
            GroupRealtimePublisher.ACTION_JOIN_APPLICATION_PENDING,
            app.getFromUserId(),
            List.of(app.getToUserId()),
            admins,
            detail);
    }

    private void notifyApplicationHandled(String groupId, GroupJoinApplication app, boolean approved) {
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(listAdminUserIds(groupId));
        if (app.getFromUserId() != null && !app.getFromUserId().isBlank()) {
            targetSet.add(app.getFromUserId().trim());
        }
        if (app.getType() == GroupJoinApplicationType.invite
            && app.getToUserId() != null && !app.getToUserId().isBlank()) {
            targetSet.add(app.getToUserId().trim());
        }
        List<String> targets = List.copyOf(targetSet);
        if (targets.isEmpty()) {
            return;
        }
        Map<String, ImAdminClient.ProfilePortrait> profiles = loadUserProfiles(List.of(app));
        Map<String, GroupMemberEnrichmentService.UserBrief> localBriefs = loadLocalBriefs(List.of(app));
        GroupProfile profile = groupProjection.findProfile(groupId).orElse(null);
        String groupAvatarUrl = profile == null
            ? null
            : avatarDefaults.resolve(profile.getAvatarUrl());
        JoinApplicationMapper.JoinApplicationItem item = JoinApplicationMapper.toItem(
            app, profiles, localBriefs, profile, groupAvatarUrl);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("applicationId", app.getId());
        detail.put("applicationType", item.applicationType());
        detail.put("fromUserId", app.getFromUserId());
        if (app.getType() == GroupJoinApplicationType.invite) {
            detail.put("toUserId", app.getToUserId());
        }
        detail.put("status", approved ? "approved" : "rejected");
        detail.put("result", approved ? "approved" : "rejected");
        if (app.getCreatedAt() != null) {
            detail.put("createdAt", app.getCreatedAt().toEpochMilli());
        }
        long updatedAtMs = app.getHandledAt() != null
            ? app.getHandledAt().toEpochMilli()
            : System.currentTimeMillis();
        detail.put("updatedAt", updatedAtMs);
        if (app.getHandledAt() != null) {
            detail.put("handledAt", app.getHandledAt().toEpochMilli());
        }
        if (app.getHandledBy() != null) {
            detail.put("handlerUserId", app.getHandledBy());
            detail.put("handledBy", app.getHandledBy());
            detail.put("handledByUserId", app.getHandledBy());
            detail.put("operatorUserId", app.getHandledBy());
        }
        if (item.handledByNickName() != null) {
            detail.put("handledByNickName", item.handledByNickName());
        }
        if (item.fromUserNickName() != null) {
            detail.put("fromUserNickName", item.fromUserNickName());
        }
        if (item.groupName() != null) {
            detail.put("groupName", item.groupName());
        }
        if (item.groupAvatarUrl() != null) {
            detail.put("groupAvatarUrl", item.groupAvatarUrl());
        }
        publishRealtime(
            groupId,
            GroupRealtimePublisher.ACTION_JOIN_APPLICATION_HANDLED,
            app.getHandledBy(),
            List.of(app.getToUserId()),
            targets,
            detail);
    }

    private void publishRealtime(String groupId,
                                 String action,
                                 String operatorUserId,
                                 List<String> memberUserIds,
                                 List<String> targetUserIds,
                                 Map<String, Object> detail) {
        long occurredAtMs = System.currentTimeMillis();
        Map<String, Object> enriched = GroupRealtimeDetailFactory.enrichWithOccurredAt(detail, occurredAtMs);
        groupRealtime.publish(
            groupId,
            action,
            operatorUserId,
            memberUserIds,
            targetUserIds,
            enriched,
            GroupChangeIdGenerator.newChangeEventId(),
            occurredAtMs,
            GroupTimelineRank.forAction(action));
    }

    private List<String> listAdminUserIds(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        String gid = groupId.trim();
        List<String> local = memberRepository.findUserIdsByGroupIdAndRoleAtLeast(gid, GroupRoleCodec.ADMIN);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (local != null) {
            for (String uid : local) {
                if (uid != null && !uid.isBlank()) {
                    out.add(uid.trim());
                }
            }
        }
        if (!out.isEmpty()) {
            return List.copyOf(out);
        }
        return im.listGroupMemberRows(gid, 0, 10_000).stream()
            .filter(r -> "Owner".equals(r.imRole()) || "Admin".equals(r.imRole()))
            .map(r -> r.userUid())
            .filter(uid -> uid != null && !uid.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}
