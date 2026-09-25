package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import com.chat99.server.im.ImAdminClient.GroupMemberRow;
import com.chat99.server.im.ImAdminClient.JoinedGroupEnriched;
import com.chat99.server.im.ImAdminClient.JoinedGroupListResult;
import com.chat99.server.im.ImGroupFetchResult;
import com.chat99.server.im.ImGroupInfoBatchResult;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(noRollbackFor = DataIntegrityViolationException.class)
public class GroupProjectionService {

    private static final Logger log = LoggerFactory.getLogger(GroupProjectionService.class);

    private static final int DUPLICATE_RELOAD_MAX_ATTEMPTS = 8;
    private static final long DUPLICATE_RELOAD_INITIAL_DELAY_MS = 25;
    private static final long DUPLICATE_RELOAD_MAX_DELAY_MS = 200;
    /** 与 GroupChangeEmitter 去重窗口一致：近期解散事件期间禁止 hydrate 写活。 */
    static final long RECENT_DISMISS_WINDOW_MS = 120_000L;
    private static final int STALE_GROUP_INFO_BATCH = 20;
    private static final int JOINED_LIST_PAGE_SIZE = 100;
    private static final int JOINED_LIST_MAX_OFFSET = 20_000;

    private final GroupProfileRepository profileRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupChangeEventRepository changeEventRepository;
    private final ImAdminClient im;
    private final GroupAvatarDefaults avatarDefaults;
    private final ObjectProvider<ImRestQueuePublisher> restQueue;
    private final ObjectProvider<UserOwnedGroupService> ownedGroupService;
    private final ObjectProvider<MeGroupsListCache> meGroupsListCache;

    @PersistenceContext
    private EntityManager entityManager;

    public GroupProjectionService(GroupProfileRepository profileRepository,
                                  GroupMemberRepository memberRepository,
                                  GroupChangeEventRepository changeEventRepository,
                                  ImAdminClient im,
                                  GroupAvatarDefaults avatarDefaults,
                                  ObjectProvider<ImRestQueuePublisher> restQueue,
                                  ObjectProvider<UserOwnedGroupService> ownedGroupService,
                                  ObjectProvider<MeGroupsListCache> meGroupsListCache) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
        this.changeEventRepository = changeEventRepository;
        this.im = im;
        this.avatarDefaults = avatarDefaults;
        this.restQueue = restQueue;
        this.ownedGroupService = ownedGroupService;
        this.meGroupsListCache = meGroupsListCache;
    }

    @Transactional
    public GroupFullSyncResult syncFullGroupFromIm(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return GroupFullSyncResult.skipped(groupId, "EMPTY_GROUP_ID");
        }
        String gid = groupId.trim();
        if (isLocallyDismissed(gid)) {
            log.info("skip syncFullGroupFromIm locally dismissed groupId={}", gid);
            return GroupFullSyncResult.skipped(gid, "LOCALLY_DISMISSED");
        }
        ImGroupFetchResult fetch = im.fetchGroupAdminInfoResult(gid);
        if (fetch.isOk()) {
            GroupAdminInfo info = fetch.info();
            if (hasRecentDismissEvent(gid) || isLocallyDismissed(gid)) {
                onGroupDismissed(gid, "syncFull_recent_or_local_dismiss");
                return GroupFullSyncResult.skipped(gid, "LOCALLY_DISMISSED");
            }
            int members = applyAliveGroupFromIm(gid, info);
            return GroupFullSyncResult.synced(gid, info.type(), members);
        }
        if (fetch.isDefinitelyGone()) {
            if (profileRepository.findById(gid).isPresent()
                || memberRepository.countByGroupId(gid) > 0) {
                onGroupDismissed(gid, "syncFull_im_gone");
                return GroupFullSyncResult.skipped(gid, "GROUP_DELETED");
            }
            return GroupFullSyncResult.skipped(gid, "GROUP_NOT_FOUND");
        }
        enqueueVerify(gid, "syncFull_transient");
        return GroupFullSyncResult.skipped(gid, "IM_TEMP_UNAVAILABLE");
    }

    /**
     * 假解散恢复：仅当 IM 明确存活且无近期真实解散事件时，清 dismissed 并全量回填成员。
     */
    @Transactional
    public RestoreResult restoreDismissedGroupFromImIfAlive(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return RestoreResult.skipped(groupId, "EMPTY_GROUP_ID");
        }
        String gid = groupId.trim();
        if (hasRecentDismissEvent(gid)) {
            return RestoreResult.skipped(gid, "RECENT_DISMISS_EVENT");
        }
        ImGroupFetchResult fetch = im.fetchGroupAdminInfoResult(gid);
        if (fetch.isDefinitelyGone()) {
            return RestoreResult.skipped(gid, "IM_GONE");
        }
        if (fetch.isTransientFailure() || !fetch.isOk() || fetch.info() == null) {
            enqueueVerify(gid, "restore_transient");
            return RestoreResult.skipped(gid, "IM_TEMP_UNAVAILABLE");
        }
        GroupAdminInfo info = fetch.info();
        GroupProfile profile = profileRepository.findById(gid).orElseGet(() -> newGroupProfile(gid));
        boolean wasDismissed = profile.isDismissed();
        profile.setDismissed(false);
        saveProfileAndFlush(profile);
        int members = applyAliveGroupFromIm(gid, info);
        maybeRestoreOwnedGroup(info);
        log.info("false dismiss restored groupId={} wasDismissed={} members={}", gid, wasDismissed, members);
        return RestoreResult.restored(gid, members, wasDismissed);
    }

    public record RestoreResult(
        String groupId,
        boolean restored,
        String reason,
        int memberRows,
        boolean wasDismissed) {

        static RestoreResult restored(String groupId, int memberRows, boolean wasDismissed) {
            return new RestoreResult(groupId, true, "RESTORED", memberRows, wasDismissed);
        }

        static RestoreResult skipped(String groupId, String reason) {
            return new RestoreResult(groupId, false, reason, 0, false);
        }
    }

    private int applyAliveGroupFromIm(String groupId, GroupAdminInfo info) {
        upsertProfileFromAdminInfo(info);
        int members = syncAllMemberPages(groupId);
        ensureOwnerMember(groupId, info);
        refreshMemberCount(groupId);
        return members;
    }

    private void maybeRestoreOwnedGroup(GroupAdminInfo info) {
        if (info == null || info.groupId() == null || info.ownerAccount() == null || info.ownerAccount().isBlank()) {
            return;
        }
        UserOwnedGroupService owned = ownedGroupService.getIfAvailable();
        if (owned == null) {
            return;
        }
        String type = info.type() == null || info.type().isBlank() ? "Public" : info.type().trim();
        owned.recordCreated(info.ownerAccount().trim(), type, info.groupId().trim());
    }

    /** IM 建群后成员列表可能短暂为空，至少写入群主行，避免后续 ensureHydrated 误判并重复 hydrate。 */
    private void ensureOwnerMember(String groupId, GroupAdminInfo info) {
        if (info.ownerAccount() == null || info.ownerAccount().isBlank()) {
            return;
        }
        String ownerId = info.ownerAccount().trim();
        if (findMember(groupId, ownerId).isPresent()) {
            return;
        }
        upsertMemberRow(groupId, ownerId, GroupRoleCodec.OWNER, null, Instant.now());
    }

    @Transactional
    public void syncUserMembershipsFromIm(String userId) {
        syncJoinedGroupsFromIm(userId);
    }

    public record GroupFullSyncResult(
        String groupId,
        boolean synced,
        String groupType,
        int memberRows,
        String reason) {

        static GroupFullSyncResult synced(String groupId, String groupType, int memberRows) {
            return new GroupFullSyncResult(groupId, true, groupType, memberRows, null);
        }

        static GroupFullSyncResult skipped(String groupId, String reason) {
            return new GroupFullSyncResult(groupId, false, null, 0, reason);
        }
    }

    private int syncAllMemberPages(String groupId) {
        if (refuseWriteIfDismissed(groupId)) {
            return 0;
        }
        if (ImAdminClient.isCommunityGroupId(groupId)) {
            return syncCommunityMemberPages(groupId);
        }
        int synced = 0;
        int offset = 0;
        int pageSize = 200;
        while (true) {
            List<GroupMemberRow> rows = im.listGroupMemberRows(groupId, offset, pageSize);
            if (rows.isEmpty()) {
                break;
            }
            for (GroupMemberRow row : rows) {
                upsertMemberRow(
                    groupId,
                    row.userUid(),
                    GroupRoleCodec.fromImRoleOrDefault(row.imRole()),
                    row.nameCard(),
                    joinInstant(row.joinTimeSec()));
                synced++;
            }
            if (rows.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return synced;
    }

    private int syncCommunityMemberPages(String groupId) {
        int synced = 0;
        String next = "";
        int guard = 0;
        while (guard++ < 10_000) {
            ImAdminClient.GroupMemberPage page = im.listGroupMemberPageByNext(groupId, next, 100);
            if (page.rows().isEmpty()) {
                break;
            }
            for (GroupMemberRow row : page.rows()) {
                upsertMemberRow(
                    groupId,
                    row.userUid(),
                    GroupRoleCodec.fromImRoleOrDefault(row.imRole()),
                    row.nameCard(),
                    joinInstant(row.joinTimeSec()));
                synced++;
            }
            if (!page.hasMore()) {
                break;
            }
            next = page.next();
        }
        return synced;
    }

    @Transactional
    public void syncJoinedGroupsFromIm(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Set<String> imIds = new LinkedHashSet<>();
        int offset = 0;
        boolean anySuccess = false;
        while (offset <= JOINED_LIST_MAX_OFFSET) {
            JoinedGroupListResult joined = im.listJoinedGroupsEnriched(userId, offset, JOINED_LIST_PAGE_SIZE);
            if (!joined.success()) {
                if (!anySuccess) {
                    log.warn("syncJoinedGroupsFromIm skipped diff heal; IM joined list failed userId={}", userId);
                    return;
                }
                log.warn("syncJoinedGroupsFromIm partial page failed userId={} offset={}", userId, offset);
                break;
            }
            anySuccess = true;
            List<JoinedGroupEnriched> page = joined.groups();
            if (page.isEmpty()) {
                break;
            }
            for (JoinedGroupEnriched row : page) {
                if (row.groupId() == null || row.groupId().isBlank()) {
                    continue;
                }
                String gid = row.groupId().trim();
                if (isLocallyDismissed(gid)) {
                    // 不在请求内复活；交 VERIFY 自愈（IM 仍在则 restore）
                    enqueueVerify(gid, "joined_list_local_dismissed");
                    log.warn("skip resurrect from joined list dismissed groupId={} userId={}", gid, userId);
                    continue;
                }
                imIds.add(gid);
                upsertProfileFromEnriched(row);
                upsertMemberRow(
                    gid,
                    userId,
                    GroupRoleCodec.fromImRoleOrDefault(row.imRole()),
                    row.nameCard(),
                    joinInstant(row.joinTimeSec()));
            }
            if (page.size() < JOINED_LIST_PAGE_SIZE) {
                break;
            }
            offset += JOINED_LIST_PAGE_SIZE;
        }
        List<String> localIds = memberRepository.findActiveGroupIdsByUserId(userId);
        List<String> staleIds = new ArrayList<>();
        for (String localId : localIds) {
            if (!imIds.contains(localId)) {
                staleIds.add(localId);
            }
        }
        healStaleMemberships(userId, staleIds);
    }

    private void healStaleMemberships(String userId, List<String> staleIds) {
        if (staleIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < staleIds.size(); i += STALE_GROUP_INFO_BATCH) {
            List<String> batch = staleIds.subList(i, Math.min(i + STALE_GROUP_INFO_BATCH, staleIds.size()));
            ImGroupInfoBatchResult batchResult = im.fetchGroupAdminInfoBatch(batch);
            Map<String, GroupAdminInfo> infos = batchResult.found();
            for (String gid : batch) {
                if (infos.containsKey(gid)) {
                    onMembersRemoved(gid, List.of(userId));
                    log.info("sync heal member left groupId={} userId={}", gid, userId);
                } else {
                    // 批失败或「未出现」一律 VERIFY，禁止在同步路径直接假解散
                    enqueueVerify(gid, batchResult.isUnreliable() ? "heal_stale_unreliable" : "heal_stale_missing");
                    log.warn("sync heal enqueue verify groupId={} userId={} unreliable={}",
                        gid, userId, batchResult.isUnreliable());
                }
            }
        }
    }

    @Transactional
    public void hydrateGroupFromIm(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        String gid = groupId.trim();
        if (skipHydrateCorruptedMWrap(gid)) {
            return;
        }
        if (isLocallyDismissed(gid)) {
            log.info("skip hydrate locally dismissed groupId={}", gid);
            return;
        }
        if (hasRecentDismissEvent(gid)) {
            log.info("hydrate converges recent dismiss event groupId={}", gid);
            onGroupDismissed(gid, "hydrate_recent_dismiss_event");
            return;
        }
        ImGroupFetchResult fetch = im.fetchGroupAdminInfoResult(gid);
        if (fetch.isDefinitelyGone()) {
            if (isCorruptedCommunityWrapOfM(gid)) {
                log.info("skip hydrate dismiss for corrupted community-wrap groupId={}", gid);
                return;
            }
            onGroupDismissed(gid, "hydrate_im_gone");
            return;
        }
        if (fetch.isTransientFailure() || !fetch.isOk()) {
            enqueueVerify(gid, "hydrate_transient");
            log.warn("hydrate skip dismiss transient groupId={} status={}", gid, fetch.status());
            return;
        }
        if (isLocallyDismissed(gid) || hasRecentDismissEvent(gid)) {
            onGroupDismissed(gid, "hydrate_dismiss_race");
            return;
        }
        upsertProfileFromAdminInfo(fetch.info());
        ImAdminClient.ImRoleFetchResult roleResult = im.getRoleInGroupResult(gid, userId);
        if (roleResult.rateLimited() || roleResult.failed()) {
            enqueueRefreshRole(gid, userId, "hydrate_role_fail");
            return;
        }
        String imRole = roleResult.role();
        if (imRole == null || "NotMember".equals(imRole)) {
            healLocalMemberIfImLeft(gid, userId);
            return;
        }
        String nameCard = im.getMemberNameCard(gid, userId).orElse(null);
        upsertMemberRow(
            gid,
            userId,
            GroupRoleCodec.fromImRoleOrDefault(imRole),
            nameCard,
            null);
    }

    /** IM 已不是成员时软删本地活行，避免本地投影残留。 */
    private void healLocalMemberIfImLeft(String groupId, String userId) {
        if (groupId == null || userId == null || userId.isBlank()) {
            return;
        }
        String uid = userId.trim();
        if (memberRepository.findByGroupIdAndUserIdActive(groupId, uid).isEmpty()) {
            return;
        }
        log.info("hydrate heal member left groupId={} userId={}", groupId, uid);
        onMembersRemoved(groupId, List.of(uid));
    }

    /**
     * REST 建群成功后本地落投影：用请求里的成员列表写入，不再分页拉取 IM 成员。
     */
    @Transactional
    public void seedGroupAfterCreate(String groupId,
                                     String creatorUserId,
                                     String groupType,
                                     String groupName,
                                     String avatarUrl,
                                     String introduction,
                                     List<String> memberUserIds) {
        if (groupId == null || groupId.isBlank() || creatorUserId == null || creatorUserId.isBlank()) {
            return;
        }
        String gid = groupId.trim();
        String owner = creatorUserId.trim();
        if (refuseWriteIfDismissed(gid)) {
            return;
        }
        Instant now = Instant.now();
        GroupProfile profile = loadOrCreateProfile(gid);
        if (profile.isDismissed()) {
            return;
        }
        String type = groupType == null ? "" : groupType.trim();
        profile.setGroupType(type);
        profile.setGroupName(groupName == null ? "" : groupName.trim());
        profile.setOwnerUserId(owner);
        if (introduction != null && !introduction.isBlank() && profile.getNotice() == null) {
            profile.setNotice(introduction.trim());
        }
        applyFaceUrl(profile, avatarUrl);
        profile.setDisplayAlias(GroupDisplayAliasUtil.compute(profile.getGroupType(), gid));

        upsertMemberRow(gid, owner, GroupRoleCodec.OWNER, null, now, null, "create");
        int members = 1;
        if (memberUserIds != null) {
            for (String peer : memberUserIds) {
                if (peer == null || peer.isBlank() || peer.trim().equals(owner)) {
                    continue;
                }
                upsertMemberRow(gid, peer.trim(), GroupRoleCodec.MEMBER, null, now, owner, "create");
                members++;
            }
        }
        profile.setMemberCount(members);
        saveProfile(profile);
        log.info("seedGroupAfterCreate groupId={} owner={} members={}", gid, owner, members);
    }

    @Transactional
    public void markChannel(String groupId) {
        GroupProfile profile = profileRepository.findById(groupId)
            .orElseThrow(() -> new IllegalStateException("CHANNEL_GROUP_PROFILE_MISSING"));
        profile.setChannel(true);
        profile.setShutUpAll(true);
        saveProfile(profile);
    }

    public boolean isChannel(String groupId) {
        return groupId != null && profileRepository.findById(groupId.trim())
            .filter(profile -> !profile.isDismissed())
            .map(GroupProfile::isChannel).orElse(false);
    }

    @Transactional
    public void syncMemberPageFromIm(String groupId, int offset, int limit) {
        if (refuseWriteIfDismissed(groupId)) {
            return;
        }
        List<GroupMemberRow> rows = im.listGroupMemberRows(groupId, offset, limit);
        for (GroupMemberRow row : rows) {
            upsertMemberRow(
                groupId,
                row.userUid(),
                GroupRoleCodec.fromImRoleOrDefault(row.imRole()),
                row.nameCard(),
                joinInstant(row.joinTimeSec()));
        }
        refreshMemberCount(groupId);
    }

    /**
     * IM 建群回调增量写入（历史/兜底）。REST {@code POST /group} 建群请走 {@link #seedGroupAfterCreate}，
     * 勿与回调并发调用，否则 group_profile 主键冲突。
     */
    @Transactional
    public void onGroupCreated(Map<String, Object> body) {
        String groupId = firstNonBlank(str(body.get("GroupId")), str(body.get("NewGroupId")));
        if (groupId == null) {
            return;
        }
        if (isLocallyDismissed(groupId)) {
            log.warn("skip onGroupCreated for locally dismissed groupId={}", groupId);
            return;
        }
        String groupType = str(body.get("Type"));
        String groupName = str(body.get("Name"));
        String owner = str(body.get("Owner_Account"));
        String faceUrl = str(body.get("FaceUrl"));
        GroupProfile profile = loadOrCreateProfile(groupId);
        if (profile.isDismissed()) {
            log.warn("skip onGroupCreated profile dismissed groupId={}", groupId);
            return;
        }
        profile.setGroupType(groupType == null ? "" : groupType);
        profile.setGroupName(groupName == null ? "" : groupName);
        profile.setOwnerUserId(owner);
        applyFaceUrl(profile, faceUrl);
        profile.setDisplayAlias(GroupDisplayAliasUtil.compute(profile.getGroupType(), groupId));
        profile.setMemberCount(Math.max(profile.getMemberCount(), 1));
        saveProfile(profile);
        if (owner != null && !owner.isBlank()) {
            upsertMemberRow(groupId, owner, GroupRoleCodec.OWNER, null, Instant.now());
        }
    }

    @Transactional
    public void onMemberAdded(String groupId, String userId, int role, Instant joinedAt) {
        upsertMemberRow(groupId, userId, role, null, joinedAt == null ? Instant.now() : joinedAt, null, null);
        refreshMemberCount(groupId);
    }

    @Transactional
    public void onMembersJoined(String groupId, List<String> userIds) {
        onMembersJoined(groupId, userIds, null, null);
    }

    /**
     * @param invitedBy 业务 user_id；仅当行上尚无值时写入
     * @param joinChannel {@link GroupMemberJoinChannel}；仅当行上尚无值时写入
     */
    @Transactional
    public void onMembersJoined(String groupId, List<String> userIds, String invitedBy, String joinChannel) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            upsertMemberRow(
                groupId, userId.trim(), GroupRoleCodec.MEMBER, null, now, invitedBy, joinChannel);
        }
        refreshMemberCount(groupId);
    }

    @Transactional
    public void onMembersRemoved(String groupId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            // 软删：写 deleted=1 + item_version，不物理删（保留行以便 tombstone 同步）
            // Tombstone change 事件由 GroupChangeEmitter 在 IM 回调路径写。
            memberRepository.bulkSoftDeleteByGroupIdAndUserId(groupId, userId.trim());
            invalidateMeGroupsUser(userId.trim());
        }
        refreshMemberCount(groupId);
    }

    @Transactional
    public void onGroupNameChanged(String groupId, String groupName) {
        if (groupId == null || groupName == null || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupProfile profile = loadOrCreateProfile(groupId);
        if (profile.isDismissed()) {
            return;
        }
        if (groupName.equals(profile.getGroupName())) {
            return;
        }
        profile.setGroupName(groupName);
        // 展示字段变更 → revision+1（snapshotRevision 一致性保证）
        profile.setRevision(profile.getRevision() + 1L);
        saveProfile(profile);
        invalidateMeGroupsGroup(groupId);
    }

    @Transactional
    public void onGroupNoticeChanged(String groupId, String notice) {
        onGroupNoticeChanged(groupId, notice, null);
    }

    @Transactional
    public void onGroupNoticeChanged(String groupId, String notice, String updatedByUserId) {
        if (groupId == null || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupProfile profile = loadOrCreateProfile(groupId);
        if (profile.isDismissed()) {
            return;
        }
        String normalized = notice == null ? "" : notice;
        boolean sameNotice = normalized.equals(profile.getNotice() == null ? "" : profile.getNotice());
        boolean samePublisher = updatedByUserId == null
            || updatedByUserId.isBlank()
            || updatedByUserId.equals(profile.getNoticeUpdatedBy());
        if (sameNotice && samePublisher) {
            return;
        }
        if (!sameNotice) {
            profile.setNotice(normalized);
            profile.setNoticeUpdatedAt(Instant.now());
        }
        if (updatedByUserId != null && !updatedByUserId.isBlank()) {
            profile.setNoticeUpdatedBy(updatedByUserId.trim());
            if (profile.getNoticeUpdatedAt() == null) {
                profile.setNoticeUpdatedAt(Instant.now());
            }
        }
        // 展示字段变更 → revision+1（snapshotRevision 一致性保证）
        profile.setRevision(profile.getRevision() + 1L);
        saveProfile(profile);
        invalidateMeGroupsGroup(groupId);
    }

    @Transactional
    public void onGroupAvatarChanged(String groupId, String faceUrl) {
        if (groupId == null || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupProfile profile = loadOrCreateProfile(groupId);
        if (profile.isDismissed()) {
            return;
        }
        applyFaceUrl(profile, faceUrl);
        profile.setAvatarVersion(profile.getAvatarVersion() + 1);
        // 展示字段变更 → revision+1（snapshotRevision 一致性保证）
        profile.setRevision(profile.getRevision() + 1L);
        saveProfile(profile);
        invalidateMeGroupsGroup(groupId);
    }

    @Transactional
    public void onMemberNameCardChanged(String groupId, String userId, String nameCard) {
        if (groupId == null || userId == null || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupMember member = loadOrCreateMember(groupId, userId);
        member.setNameCard(nameCard);
        saveMember(member);
    }

    @Transactional
    public void onMemberRoleChanged(String groupId, String userId, String imRole) {
        if (groupId == null || userId == null || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupMember member = loadOrCreateMember(groupId, userId);
        member.setRole(GroupRoleCodec.fromImRoleOrDefault(imRole));
        saveMember(member);
    }

    /**
     * 个人禁言投影。{@code mutedUntilSec <= 0} 表示解除禁言。
     * 真相源：写路径在 IM 成功后调用；旁路靠 IM 回调。
     */
    @Transactional
    public void onMemberMutedUntilChanged(String groupId, String userId, long mutedUntilSec) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()
            || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupMember member = loadOrCreateMember(groupId.trim(), userId.trim());
        if (mutedUntilSec <= 0) {
            member.setMutedUntil(null);
        } else {
            member.setMutedUntil(mutedUntilSec);
        }
        saveMember(member);
    }

    /** 全员禁言投影。 */
    @Transactional
    public void onShutUpAllChanged(String groupId, boolean shutUpAll) {
        if (groupId == null || groupId.isBlank() || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupProfile profile = loadOrCreateProfile(groupId.trim());
        if (profile.isDismissed()) {
            return;
        }
        profile.setShutUpAll(shutUpAll);
        saveProfile(profile);
    }

    /** 仍有效的个人禁言截止秒；过期或未禁返回 empty。 */
    public java.util.Optional<Long> activeMutedUntil(String groupId, String userId) {
        if (groupId == null || userId == null) {
            return java.util.Optional.empty();
        }
        return memberRepository.findById(new GroupMemberId(groupId.trim(), userId.trim()))
            .map(GroupMember::getMutedUntil)
            .flatMap(until -> {
                if (until == null || until <= 0) {
                    return java.util.Optional.empty();
                }
                long nowSec = Instant.now().getEpochSecond();
                return until > nowSec ? java.util.Optional.of(until) : java.util.Optional.empty();
            });
    }

    public boolean isShutUpAll(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return false;
        }
        return profileRepository.findById(groupId.trim())
            .map(GroupProfile::isShutUpAll)
            .orElse(false);
    }

    public List<GroupMember> listActivelyMutedMembers(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        long nowSec = Instant.now().getEpochSecond();
        return memberRepository.findActivelyMutedByGroupId(groupId.trim(), nowSec);
    }

    @Transactional
    public void onOwnerChanged(String groupId, String newOwnerUserId, String oldOwnerUserId) {
        if (groupId == null || refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupProfile profile = loadOrCreateProfile(groupId);
        if (profile.isDismissed()) {
            return;
        }
        profile.setOwnerUserId(newOwnerUserId);
        saveProfile(profile);
        if (oldOwnerUserId != null && !oldOwnerUserId.isBlank()) {
            memberRepository.findById(new GroupMemberId(groupId, oldOwnerUserId)).ifPresent(member -> {
                member.setRole(GroupRoleCodec.MEMBER);
                saveMember(member);
            });
        }
        if (newOwnerUserId != null && !newOwnerUserId.isBlank()) {
            upsertMemberRow(groupId, newOwnerUserId, GroupRoleCodec.OWNER, null, null);
        }
    }

    @Transactional
    public void onGroupDismissed(String groupId) {
        onGroupDismissed(groupId, "unspecified");
    }

    @Transactional
    public void onGroupDismissed(String groupId, String source) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        String gid = groupId.trim();
        java.util.Optional<GroupProfile> existing = profileRepository.findById(gid);
        if (existing.isPresent() && existing.get().isDismissed()
            && memberRepository.countActiveByGroupId(gid) == 0) {
            // 幂等：已解散且成员已全部软删，不再抬 revision / 重复软删（小时级 verify 会反复走到这里）
            log.debug("group dismissed noop already dismissed groupId={} source={}", gid, source);
            return;
        }
        log.info("group dismissed groupId={} source={}", gid, source == null ? "unspecified" : source);
        List<String> memberUserIds = memberRepository.findUserIdsByGroupId(gid);
        GroupProfile profile = existing.orElseGet(() -> newGroupProfile(gid));
        profile.setDismissed(true);
        // 群解散软删：profile.revision+1 让所有客户端能感知
        profile.setRevision(profile.getRevision() + 1);
        saveProfileAndFlush(profile);
        // 群成员行软删：deleted=1 + item_version++（tombstone 由 GroupChangeEmitter 在 IM 回调路径写）
        memberRepository.bulkSoftDeleteByGroupId(gid);
        invalidateMeGroupsUsers(memberUserIds);
    }

    private void enqueueVerify(String groupId, String reason) {
        ImRestQueuePublisher publisher = restQueue.getIfAvailable();
        if (publisher != null) {
            publisher.enqueueVerifyGroupExists(groupId, reason);
        }
    }

    private void enqueueRefreshRole(String groupId, String userId, String reason) {
        ImRestQueuePublisher publisher = restQueue.getIfAvailable();
        if (publisher != null && userId != null && !userId.isBlank()) {
            publisher.enqueueRefreshRole(groupId, userId, reason);
        }
    }

    /** 解散前快照本地成员，供 targets 与 IM 列表并集。 */
    public List<String> listLocalMemberUserIds(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        return memberRepository.findUserIdsByGroupId(groupId.trim());
    }

    /** 未解散群资料；已解散返回 empty。 */
    public Optional<GroupProfile> findProfile(String groupId) {
        return profileRepository.findById(groupId).filter(profile -> !profile.isDismissed());
    }

    /** 含已解散行，供 ensureHydrated 等判断。 */
    public Optional<GroupProfile> findProfileIncludingDismissed(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        return profileRepository.findById(groupId.trim());
    }

    public boolean isLocallyDismissed(String groupId) {
        return findProfileIncludingDismissed(groupId).map(GroupProfile::isDismissed).orElse(false);
    }

    /** {@code @TGS#_@TGS#m…} 错包迁后普通群 ID。 */
    static boolean isCorruptedCommunityWrapOfM(String groupId) {
        if (groupId == null) {
            return false;
        }
        String gid = groupId.trim();
        return gid.startsWith("@TGS#_@TGS#m") && gid.length() > "@TGS#_@TGS#".length();
    }

    static String unwrapCorruptedCommunityM(String groupId) {
        if (!isCorruptedCommunityWrapOfM(groupId)) {
            return null;
        }
        return groupId.trim().substring("@TGS#_@TGS#".length());
    }

    private boolean skipHydrateCorruptedMWrap(String gid) {
        if (!isCorruptedCommunityWrapOfM(gid)) {
            return false;
        }
        String liveM = unwrapCorruptedCommunityM(gid);
        if (liveM == null || liveM.isBlank()) {
            return false;
        }
        Optional<GroupProfile> live = findProfile(liveM);
        if (live.isPresent()) {
            log.info("skip hydrate corrupted community-wrap of live m-id groupId={} live={}", gid, liveM);
            return true;
        }
        return false;
    }

    public Optional<GroupMember> findMember(String groupId, String userId) {
        return memberRepository.findById(new GroupMemberId(groupId, userId));
    }

    private boolean hasRecentDismissEvent(String groupId) {
        if (groupId == null || groupId.isBlank() || changeEventRepository == null) {
            return false;
        }
        long minOccurredAt = System.currentTimeMillis() - RECENT_DISMISS_WINDOW_MS;
        List<GroupChangeEvent> recent = changeEventRepository.findRecentByGroupAndAction(
            groupId.trim(),
            GroupRealtimePublisher.ACTION_GROUP_DISMISSED,
            minOccurredAt,
            PageRequest.of(0, 1));
        return !recent.isEmpty();
    }

    private boolean refuseWriteIfDismissed(String groupId) {
        if (isLocallyDismissed(groupId)) {
            log.debug("refuse projection write dismissed groupId={}", groupId);
            return true;
        }
        return false;
    }

    private void upsertProfileFromEnriched(JoinedGroupEnriched row) {
        if (row.groupId() == null || row.groupId().isBlank()) {
            return;
        }
        if (isLocallyDismissed(row.groupId())) {
            log.info("skip upsertProfileFromEnriched dismissed groupId={}", row.groupId());
            return;
        }
        GroupProfile profile = loadOrCreateProfile(row.groupId());
        if (profile.isDismissed()) {
            log.info("skip upsertProfileFromEnriched after load dismissed groupId={}", row.groupId());
            return;
        }
        if (row.groupType() != null && !row.groupType().isBlank()) {
            profile.setGroupType(row.groupType());
        }
        if (row.groupName() != null) {
            profile.setGroupName(row.groupName());
        }
        if (row.ownerUserId() != null) {
            profile.setOwnerUserId(row.ownerUserId());
        }
        if (row.notice() != null) {
            profile.setNotice(row.notice());
        }
        if (row.memberCount() != null) {
            profile.setMemberCount(row.memberCount());
        }
        applyFaceUrl(profile, row.faceUrl());
        profile.setDisplayAlias(GroupDisplayAliasUtil.compute(profile.getGroupType(), row.groupId()));
        saveProfile(profile);
    }

    private void upsertProfileFromAdminInfo(GroupAdminInfo info) {
        if (info == null || info.groupId() == null || info.groupId().isBlank()) {
            return;
        }
        if (isLocallyDismissed(info.groupId())) {
            log.info("skip upsertProfileFromAdminInfo dismissed groupId={}", info.groupId());
            return;
        }
        GroupProfile profile = loadOrCreateProfile(info.groupId());
        if (profile.isDismissed()) {
            log.info("skip upsertProfileFromAdminInfo after load dismissed groupId={}", info.groupId());
            return;
        }
        profile.setGroupType(info.type() == null ? "" : info.type());
        profile.setGroupName(info.name() == null ? "" : info.name());
        profile.setOwnerUserId(info.ownerAccount());
        if (info.memberNum() != null) {
            profile.setMemberCount(info.memberNum());
        }
        if (info.notification() != null) {
            profile.setNotice(info.notification());
        }
        applyFaceUrl(profile, info.faceUrl());
        profile.setDisplayAlias(GroupDisplayAliasUtil.compute(profile.getGroupType(), info.groupId()));
        saveProfile(profile);
    }

    private void upsertMemberRow(String groupId, String userId, int role, String nameCard, Instant joinedAt) {
        upsertMemberRow(groupId, userId, role, nameCard, joinedAt, null, null);
    }

    private void upsertMemberRow(String groupId, String userId, int role, String nameCard, Instant joinedAt,
                                 String invitedBy, String joinChannel) {
        if (groupId == null || userId == null || userId.isBlank()) {
            return;
        }
        if (refuseWriteIfDismissed(groupId)) {
            return;
        }
        GroupMember member = loadOrCreateMember(groupId, userId);
        // 复活：IM 侧仍存在的成员，本地软删行（deleted=1）需清 tombstone 并 item_version++
        if (member.isDeleted()) {
            member.setDeleted(false);
            member.setDeletedAt(null);
            member.setItemVersion(member.getItemVersion() + 1L);
        }
        member.setRole(role);
        if (nameCard != null) {
            member.setNameCard(nameCard);
        }
        if (joinedAt != null && member.getJoinedAt() == null) {
            member.setJoinedAt(joinedAt);
        }
        if (invitedBy != null && !invitedBy.isBlank() && member.getInvitedBy() == null) {
            member.setInvitedBy(invitedBy.trim());
        }
        if (joinChannel != null && !joinChannel.isBlank() && member.getJoinChannel() == null) {
            member.setJoinChannel(joinChannel.trim());
        }
        saveMember(member);
    }

    private GroupProfile loadOrCreateProfile(String groupId) {
        Optional<GroupProfile> existing = profileRepository.findById(groupId);
        if (existing.isPresent()) {
            return existing.get();
        }
        GroupProfile created = newGroupProfile(groupId);
        try {
            return profileRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            log.debug("group profile insert race groupId={} reloading", groupId);
            return reloadAfterDuplicateInsert(
                "group profile " + groupId,
                () -> profileRepository.findById(groupId),
                ex);
        }
    }

    private static GroupProfile newGroupProfile(String groupId) {
        GroupProfile created = new GroupProfile();
        created.setGroupId(groupId);
        created.setGroupType("");
        created.setGroupName("");
        created.setDisplayAlias("");
        created.setMemberCount(0);
        created.setDismissed(false);
        return created;
    }

    private static boolean isDuplicateKey(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && (message.contains("Duplicate entry")
                || message.contains("duplicate key")
                || message.contains("UNIQUE constraint failed"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private GroupMember loadOrCreateMember(String groupId, String userId) {
        GroupMemberId id = new GroupMemberId(groupId, userId);
        Optional<GroupMember> existing = memberRepository.findById(id);
        if (existing.isPresent()) {
            return existing.get();
        }
        GroupMember created = new GroupMember();
        created.setGroupId(groupId);
        created.setUserId(userId);
        created.setRole(GroupRoleCodec.MEMBER);
        try {
            return memberRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            log.debug("group member insert race groupId={} userId={} reloading", groupId, userId);
            return reloadAfterDuplicateInsert(
                "group member " + groupId + "/" + userId,
                () -> memberRepository.findById(id),
                ex);
        }
    }

    private void saveMember(GroupMember member) {
        try {
            memberRepository.save(member);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            clearPersistenceContextAfterDuplicate();
            GroupMember existing = reloadAfterDuplicateInsert(
                "group member " + member.getGroupId() + "/" + member.getUserId(),
                () -> memberRepository.findById(new GroupMemberId(member.getGroupId(), member.getUserId())),
                ex);
            existing.setRole(member.getRole());
            if (member.getNameCard() != null) {
                existing.setNameCard(member.getNameCard());
            }
            existing.setMutedUntil(member.getMutedUntil());
            if (member.getJoinedAt() != null && existing.getJoinedAt() == null) {
                existing.setJoinedAt(member.getJoinedAt());
            }
            if (member.getInvitedBy() != null && existing.getInvitedBy() == null) {
                existing.setInvitedBy(member.getInvitedBy());
            }
            if (member.getJoinChannel() != null && existing.getJoinChannel() == null) {
                existing.setJoinChannel(member.getJoinChannel());
            }
            memberRepository.save(existing);
            invalidateMeGroupsUser(existing.getUserId());
            return;
        }
        invalidateMeGroupsUser(member.getUserId());
    }

    private void invalidateMeGroupsUser(String userId) {
        MeGroupsListCache cache = meGroupsListCache.getIfAvailable();
        if (cache != null) {
            cache.invalidateUser(userId);
        }
    }

    private void invalidateMeGroupsUsers(List<String> userIds) {
        MeGroupsListCache cache = meGroupsListCache.getIfAvailable();
        if (cache != null) {
            cache.invalidateUsers(userIds);
        }
    }

    private void invalidateMeGroupsGroup(String groupId) {
        MeGroupsListCache cache = meGroupsListCache.getIfAvailable();
        if (cache != null) {
            cache.invalidateGroup(groupId);
        }
    }

    private void saveProfile(GroupProfile profile) {
        try {
            profileRepository.save(profile);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            clearPersistenceContextAfterDuplicate();
            GroupProfile existing = reloadAfterDuplicateInsert(
                "group profile " + profile.getGroupId(),
                () -> profileRepository.findById(profile.getGroupId()),
                ex);
            mergeProfileFields(existing, profile);
            profileRepository.save(existing);
        }
    }

    /** 解散墓碑必须立即对并发读可见。 */
    private void saveProfileAndFlush(GroupProfile profile) {
        try {
            profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            clearPersistenceContextAfterDuplicate();
            GroupProfile existing = reloadAfterDuplicateInsert(
                "group profile " + profile.getGroupId(),
                () -> profileRepository.findById(profile.getGroupId()),
                ex);
            mergeProfileFields(existing, profile);
            existing.setDismissed(true);
            profileRepository.saveAndFlush(existing);
        }
    }

    /**
     * IM 建群回调与 {@code POST /group} 可能并发 insert 同一行；duplicate 后对方事务未必已提交，
     * 需短暂重试再读，避免误报「missing after duplicate insert」。
     */
    <T> T reloadAfterDuplicateInsert(String label,
                                     Supplier<Optional<T>> loader,
                                     DataIntegrityViolationException cause) {
        clearPersistenceContextAfterDuplicate();
        long delayMs = DUPLICATE_RELOAD_INITIAL_DELAY_MS;
        for (int attempt = 1; attempt <= DUPLICATE_RELOAD_MAX_ATTEMPTS; attempt++) {
            Optional<T> found = loader.get();
            if (found.isPresent()) {
                if (attempt > 1) {
                    log.debug("{} visible after duplicate insert attempt={}", label, attempt);
                }
                return found.get();
            }
            if (attempt < DUPLICATE_RELOAD_MAX_ATTEMPTS) {
                sleepQuietly(delayMs);
                delayMs = Math.min(delayMs * 2, DUPLICATE_RELOAD_MAX_DELAY_MS);
            }
        }
        throw new IllegalStateException(label + " missing after duplicate insert", cause);
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void clearPersistenceContextAfterDuplicate() {
        if (entityManager != null) {
            entityManager.clear();
        }
    }

    private static void mergeProfileFields(GroupProfile target, GroupProfile source) {
        if (source.getGroupType() != null && !source.getGroupType().isBlank()) {
            target.setGroupType(source.getGroupType());
        }
        if (source.getGroupName() != null) {
            target.setGroupName(source.getGroupName());
        }
        if (source.getOwnerUserId() != null) {
            target.setOwnerUserId(source.getOwnerUserId());
        }
        if (source.getNotice() != null) {
            target.setNotice(source.getNotice());
        }
        if (source.getNoticeUpdatedAt() != null) {
            target.setNoticeUpdatedAt(source.getNoticeUpdatedAt());
        }
        if (source.getNoticeUpdatedBy() != null && !source.getNoticeUpdatedBy().isBlank()) {
            target.setNoticeUpdatedBy(source.getNoticeUpdatedBy());
        }
        if (source.getMemberCount() > 0) {
            target.setMemberCount(source.getMemberCount());
        }
        if (source.getAvatarUrl() != null) {
            target.setAvatarUrl(source.getAvatarUrl());
        }
        if (source.getAvatarPreviewUrl() != null) {
            target.setAvatarPreviewUrl(source.getAvatarPreviewUrl());
        }
        if (source.getDisplayAlias() != null && !source.getDisplayAlias().isBlank()) {
            target.setDisplayAlias(source.getDisplayAlias());
        }
        // 已解散不可被并发 upsert 写回 false
        if (target.isDismissed() || source.isDismissed()) {
            target.setDismissed(true);
        } else {
            target.setDismissed(false);
        }
        target.setShutUpAll(source.isShutUpAll());
        target.setChannel(target.isChannel() || source.isChannel());
    }

    private void applyFaceUrl(GroupProfile profile, String faceUrl) {
        String resolved = avatarDefaults.resolve(faceUrl);
        if (resolved == null) {
            return;
        }
        profile.setAvatarPreviewUrl(resolved);
        if (resolved.contains("_thumb.jpg")) {
            profile.setAvatarUrl(resolved);
        } else if (resolved.contains("_preview.jpg")) {
            profile.setAvatarUrl(resolved.replace("_preview.jpg", "_thumb.jpg"));
        } else {
            profile.setAvatarUrl(resolved);
        }
    }

    private void refreshMemberCount(String groupId) {
        if (refuseWriteIfDismissed(groupId)) {
            return;
        }
        int count = im.countGroupMembers(groupId);
        if (count <= 0) {
            count = (int) memberRepository.countActiveByGroupId(groupId);
        }
        final int memberCount = count;
        profileRepository.findById(groupId).ifPresent(profile -> {
            if (profile.isDismissed()) {
                return;
            }
            profile.setMemberCount(memberCount);
            saveProfile(profile);
        });
    }

    private static Instant joinInstant(long joinTimeSec) {
        return joinTimeSec > 0 ? Instant.ofEpochSecond(joinTimeSec) : null;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
