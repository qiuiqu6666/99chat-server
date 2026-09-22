/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.im;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.group.GroupChangeEmitter;
import com.chat99.server.group.GroupChangeEventSource;
import com.chat99.server.group.GroupImSyncService;
import com.chat99.server.group.GroupChangeIdGenerator;
import com.chat99.server.group.GroupMember;
import com.chat99.server.group.GroupMemberJoinChannel;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.group.GroupRealtimeDetailFactory;
import com.chat99.server.group.GroupRoleCodec;
import com.chat99.server.group.GroupSystemNoticeService;
import com.chat99.server.group.GroupSystemNoticeType;
import com.chat99.server.group.GroupTimelineRank;
import com.chat99.server.im.GroupMemberCacheService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImCallbackVerifier;
import com.chat99.server.im.ImPushDedupStore;
import com.chat99.server.push.PushConfigService;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImGroupRealtimeCallbackService {
    private static final Logger log = LoggerFactory.getLogger(ImGroupRealtimeCallbackService.class);
    static final String CMD_INFO_CHANGED = "Group.CallbackAfterGroupInfoChanged";
    static final String CMD_MEMBER_JOIN = "Group.CallbackAfterNewMemberJoin";
    static final String CMD_MEMBER_EXIT = "Group.CallbackAfterMemberExit";
    static final String CMD_MEMBER_INVITED = "Group.CallbackAfterMemberInvited";
    static final String CMD_MEMBER_KICKED = "Group.CallbackAfterMemberKicked";
    static final String CMD_OWNER_CHANGED = "Group.CallbackAfterChangeGroupOwner";
    static final String CMD_GROUP_DESTROY = "Group.CallbackAfterGroupDestroyed";
    static final String CMD_MEMBER_STATE = "Group.CallbackOnMemberStateChange";
    static final String CMD_MEMBER_FIELD = "Group.CallbackAfterMemberFieldChanged";
    private static final int MAX_FANOUT_MEMBERS = 10000;
    private static final Set<String> REALTIME_COMMANDS = Set.of("Group.CallbackAfterGroupInfoChanged", "Group.CallbackAfterNewMemberJoin", "Group.CallbackAfterMemberExit", "Group.CallbackAfterMemberInvited", "Group.CallbackAfterMemberKicked", "Group.CallbackAfterChangeGroupOwner", "Group.CallbackAfterGroupDestroyed", "Group.CallbackOnMemberStateChange", "Group.CallbackAfterMemberFieldChanged");
    private final PushConfigService pushConfig;
    private final AppSettingService settings;
    private final ImCallbackVerifier callbackVerifier;
    private final ImAdminClient imAdmin;
    private final GroupMemberCacheService groupMemberCache;
    private final GroupRealtimePublisher groupRealtime;
    private final GroupChangeEmitter groupChangeEmitter;
    private final ImPushDedupStore dedupStore;
    private final GroupProjectionService groupProjection;
    private final GroupSystemNoticeService systemNoticeService;
    private final ImUserIdService imUserIdService;
    private final GroupImSyncService groupImSyncService;
    private final ObjectMapper json;

    public ImGroupRealtimeCallbackService(PushConfigService pushConfig, AppSettingService settings, ImCallbackVerifier callbackVerifier, ImAdminClient imAdmin, GroupMemberCacheService groupMemberCache, GroupRealtimePublisher groupRealtime, GroupChangeEmitter groupChangeEmitter, ImPushDedupStore dedupStore, GroupProjectionService groupProjection, GroupSystemNoticeService systemNoticeService, ImUserIdService imUserIdService, GroupImSyncService groupImSyncService, ObjectMapper json) {
        this.pushConfig = pushConfig;
        this.settings = settings;
        this.callbackVerifier = callbackVerifier;
        this.imAdmin = imAdmin;
        this.groupMemberCache = groupMemberCache;
        this.groupRealtime = groupRealtime;
        this.groupChangeEmitter = groupChangeEmitter;
        this.dedupStore = dedupStore;
        this.groupProjection = groupProjection;
        this.systemNoticeService = systemNoticeService;
        this.imUserIdService = imUserIdService;
        this.groupImSyncService = groupImSyncService;
        this.json = json;
    }

    public void handle(String sdkAppId, String command, String callbackToken, String sign, String requestTime, String rawBody) {
        Map<String, Object> body = this.parseBody(rawBody);
        String resolvedCommand = ImGroupRealtimeCallbackService.firstNonBlank(command, ImGroupRealtimeCallbackService.str(body.get("CallbackCommand")));
        if (resolvedCommand == null || !REALTIME_COMMANDS.contains(resolvedCommand)) {
            return;
        }
        this.validateSdkAppId(sdkAppId);
        if (sign != null && !sign.isBlank()) {
            this.callbackVerifier.verifySignature(sign, requestTime);
        } else {
            this.callbackVerifier.verifyQueryToken(callbackToken);
        }
        String dedupKey = "grt|" + resolvedCommand + "|" + ImGroupRealtimeCallbackService.str(body.get("GroupId")) + "|" + rawBody.hashCode();
        if (!this.dedupStore.markIfNew(dedupKey)) {
            log.debug("group realtime duplicate command={}", (Object)resolvedCommand);
            return;
        }
        switch (resolvedCommand) {
            case "Group.CallbackAfterGroupInfoChanged": {
                this.handleInfoChanged(body);
                break;
            }
            case "Group.CallbackAfterNewMemberJoin": {
                this.handleMemberJoin(body);
                break;
            }
            case "Group.CallbackAfterMemberExit": {
                this.handleMemberExit(body);
                break;
            }
            case "Group.CallbackAfterMemberInvited": {
                this.handleMemberInvited(body);
                break;
            }
            case "Group.CallbackAfterMemberKicked": {
                this.handleMemberKicked(body);
                break;
            }
            case "Group.CallbackAfterChangeGroupOwner": {
                this.handleOwnerChanged(body);
                break;
            }
            case "Group.CallbackAfterGroupDestroyed": {
                this.handleGroupDestroyed(body);
                break;
            }
            case "Group.CallbackOnMemberStateChange": {
                this.handleMemberState(body);
                break;
            }
            case "Group.CallbackAfterMemberFieldChanged": {
                this.handleMemberField(body);
                break;
            }
        }
    }

    private void handleInfoChanged(Map<String, Object> body) {
        GroupProfile profile;
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        Set<String> fields = ImGroupRealtimeCallbackService.resolveUpdatedFields(body);
        List<String> targets = this.memberTargets(groupId, null);
        if (fields.contains("Notification")) {
            String notice = ImGroupRealtimeCallbackService.str(body.get("Notification"));
            this.groupProjection.onGroupNoticeChanged(groupId, notice, toBusinessId(operator));
            this.groupChangeEmitter.emitDisplayInfoChanged(
                groupId, "group_notice_changed", toBusinessId(operator), Instant.now(),
                GroupChangeEventSource.IM_CALLBACK);
        }
        if (fields.contains("Name")) {
            String name = ImGroupRealtimeCallbackService.str(body.get("Name"));
            this.groupProjection.onGroupNameChanged(groupId, name);
            this.groupChangeEmitter.emitDisplayInfoChanged(
                groupId, "group_name_changed", toBusinessId(operator), Instant.now(),
                GroupChangeEventSource.IM_CALLBACK);
        }
        if (fields.contains("FaceUrl")) {
            String faceUrl = ImGroupRealtimeCallbackService.str(body.get("FaceUrl"));
            this.groupProjection.onGroupAvatarChanged(groupId, faceUrl);
            this.groupChangeEmitter.emitDisplayInfoChanged(
                groupId, "group_avatar_changed", toBusinessId(operator), Instant.now(),
                GroupChangeEventSource.IM_CALLBACK);
        }
        if (fields.contains("ShutUpAllMember")) {
            String shutUp = ImGroupRealtimeCallbackService.str(body.get("ShutUpAllMember"));
            boolean on = "On".equalsIgnoreCase(shutUp);
            this.groupProjection.onShutUpAllChanged(groupId, on);
            // 软全员禁言：控制台误开原生禁言时，投影记下业务态后强制关掉 IM 开关
            if (on) {
                try {
                    this.imAdmin.modifyGroupMuteAll(groupId, false);
                } catch (Exception e) {
                    log.warn("force IM ShutUpAllMember Off failed groupId={} err={}", groupId, e.getMessage());
                }
            }
            this.publish(groupId, "group_mute_all_changed", operator, List.of(), targets,
                ImGroupRealtimeCallbackService.mapOf("shutUpAllMember", on ? "On" : "Off"));
        }
        if (fields.contains("ApplyJoinOption")) {
            this.publish(groupId, "group_join_option_changed", operator, List.of(), targets, ImGroupRealtimeCallbackService.mapOf("applyJoinOption", ImGroupRealtimeCallbackService.str(body.get("ApplyJoinOption"))));
        }
    }

    private void handleMemberJoin(Map<String, Object> body) {
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        String joinType = ImGroupRealtimeCallbackService.str(body.get("JoinType"));
        List<String> membersIm = ImGroupRealtimeCallbackService.extractMemberAccounts(body.get("NewMemberList"));
        Instant occurredAt = Instant.now();
        this.groupMemberCache.invalidate(groupId);
        String invitedBy = null;
        String joinChannel = null;
        if ("Apply".equalsIgnoreCase(joinType)) {
            joinChannel = GroupMemberJoinChannel.GROUP_ID;
        } else if ("Invited".equalsIgnoreCase(joinType)) {
            String opBiz = toBusinessId(operator);
            if (opBiz != null && !opBiz.isBlank()) {
                invitedBy = opBiz;
                joinChannel = GroupMemberJoinChannel.INVITE;
            }
        }
        this.groupProjection.onMembersJoined(groupId, membersIm, invitedBy, joinChannel);
        List<String> members = toBusinessIds(membersIm);
        this.groupImSyncService.enqueueMembershipReconcile(groupId, members, "im_callback_join");
        if (this.groupChangeEmitter.hasRecentDuplicate(groupId, "member_added", members)) {
            log.debug("skip duplicate member_added callback groupId={}", (Object)groupId);
            return;
        }
        List<String> targets = this.memberTargets(groupId, members);
        this.groupChangeEmitter.emitMemberAdded(groupId, toBusinessId(operator), members, targets, occurredAt, "im_callback");
        log.info("group realtime published groupId={} action={} targets={}", groupId, "member_added", targets.size());
    }

    private void handleMemberInvited(Map<String, Object> body) {
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        List<String> membersIm = ImGroupRealtimeCallbackService.extractMemberAccounts(body.get("NewMemberList"));
        Instant occurredAt = Instant.now();
        this.groupMemberCache.invalidate(groupId);
        String opBiz = toBusinessId(operator);
        String invitedBy = (opBiz == null || opBiz.isBlank()) ? null : opBiz;
        String joinChannel = invitedBy == null ? null : GroupMemberJoinChannel.INVITE;
        this.groupProjection.onMembersJoined(groupId, membersIm, invitedBy, joinChannel);
        List<String> members = toBusinessIds(membersIm);
        this.groupImSyncService.enqueueMembershipReconcile(groupId, members, "im_callback_invite");
        if (this.groupChangeEmitter.hasRecentDuplicate(groupId, "member_added", members)) {
            log.debug("skip duplicate member_added callback groupId={}", (Object)groupId);
            return;
        }
        List<String> targets = this.memberTargets(groupId, members);
        this.groupChangeEmitter.emitMemberAdded(groupId, toBusinessId(operator), members, targets, occurredAt, "im_callback");
        log.info("group realtime published groupId={} action={} targets={}", groupId, "member_added", targets.size());
    }

    private void handleMemberExit(Map<String, Object> body) {
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        List<String> membersIm = ImGroupRealtimeCallbackService.extractMemberAccounts(body.get("ExitMemberList"));
        if (membersIm.isEmpty() && operator != null) {
            membersIm = List.of(operator);
        }
        Instant occurredAt = Instant.now();
        this.groupMemberCache.invalidate(groupId);
        this.groupProjection.onMembersRemoved(groupId, membersIm);
        List<String> members = toBusinessIds(membersIm);
        this.groupImSyncService.enqueueMembershipReconcile(groupId, members, "im_callback_exit");
        if (this.groupChangeEmitter.hasRecentDuplicate(groupId, "member_left", members)) {
            log.debug("skip duplicate member_left callback groupId={}", (Object)groupId);
            return;
        }
        List<String> targets = this.memberTargets(groupId, members);
        this.groupChangeEmitter.emitMemberLeftOrRemoved(groupId, "member_left", toBusinessId(operator), members, targets, occurredAt, "im_callback");
        log.info("group realtime published groupId={} action={} targets={}", groupId, "member_left", targets.size());
    }

    private void handleMemberKicked(Map<String, Object> body) {
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        List<String> membersIm = ImGroupRealtimeCallbackService.extractMemberAccounts(body.get("KickedMemberList"));
        Instant occurredAt = Instant.now();
        this.groupMemberCache.invalidate(groupId);
        this.groupProjection.onMembersRemoved(groupId, membersIm);
        List<String> members = toBusinessIds(membersIm);
        this.groupImSyncService.enqueueMembershipReconcile(groupId, members, "im_callback_kick");
        if (this.groupChangeEmitter.hasRecentDuplicate(groupId, "member_removed", members)) {
            log.debug("skip duplicate member_removed callback groupId={}", (Object)groupId);
            return;
        }
        List<String> targets = ImGroupRealtimeCallbackService.union(this.memberTargets(groupId, null), members);
        this.groupChangeEmitter.emitMemberLeftOrRemoved(groupId, "member_removed", toBusinessId(operator), members, targets, occurredAt, "im_callback");
        log.info("group realtime published groupId={} action={} targets={}", groupId, "member_removed", targets.size());
    }

    private void handleOwnerChanged(Map<String, Object> body) {
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        String newOwner = ImGroupRealtimeCallbackService.firstNonBlank(ImGroupRealtimeCallbackService.str(body.get("NewOwner_Account")), ImGroupRealtimeCallbackService.str(body.get("Owner_Account")));
        String oldOwner = ImGroupRealtimeCallbackService.str(body.get("OldOwner_Account"));
        this.groupProjection.onOwnerChanged(groupId, newOwner, oldOwner);
        List<String> targets = this.memberTargets(groupId, null);
        String newOwnerBiz = toBusinessId(newOwner);
        String oldOwnerBiz = toBusinessId(oldOwner);
        this.publish(groupId, "owner_changed", operator, List.of(), targets, GroupRealtimeDetailFactory.ownerChanged((String)newOwnerBiz, (String)oldOwnerBiz, (Instant)this.profileUpdatedAt(groupId)));
        String noticeOperator = ImGroupRealtimeCallbackService.firstNonBlank(operator, oldOwner);
        if (groupId != null && newOwner != null && noticeOperator != null) {
            this.systemNoticeService.recordAndPublish(groupId, GroupSystemNoticeType.transfer_owner, toBusinessId(noticeOperator), newOwnerBiz);
        }
    }

    private void handleGroupDestroyed(Map<String, Object> body) {
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        List<String> localIds = toBusinessIds(this.groupProjection.listLocalMemberUserIds(groupId));
        List<String> targets = ImGroupRealtimeCallbackService.union(
            ImGroupRealtimeCallbackService.union(localIds, this.memberTargets(groupId, null)),
            operator == null ? List.of() : List.of(toBusinessId(operator)));
        Instant occurredAt = Instant.now();
        this.groupMemberCache.invalidate(groupId);
        this.groupProjection.onGroupDismissed(groupId);
        if (this.groupChangeEmitter.hasRecentDuplicate(groupId, "group_dismissed", List.of())) {
            log.debug("skip duplicate group_dismissed callback groupId={}", (Object)groupId);
            return;
        }
        this.groupChangeEmitter.emitGroupDismissed(groupId, toBusinessId(operator), targets, occurredAt, "im_callback");
        log.info("group realtime published groupId={} action={} targets={}", groupId, "group_dismissed", targets.size());
    }

    private void handleMemberState(Map<String, Object> body) {
        List list;
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        Object rawList = body.get("MemberList");
        if (!(rawList instanceof List) || (list = (List)rawList).isEmpty()) {
            return;
        }
        this.groupMemberCache.invalidate(groupId);
        List<String> targets = this.memberTargets(groupId, null);
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map m = (Map)item;
            String memberId = ImGroupRealtimeCallbackService.str(m.get("Member_Account"));
            String role = ImGroupRealtimeCallbackService.str(m.get("Role"));
            boolean hasMutedUntilKey = m.containsKey("MutedUntil");
            String mutedUntilRaw = ImGroupRealtimeCallbackService.str(m.get("MutedUntil"));
            if (role != null) {
                int previousRole = this.groupProjection.findMember(groupId, memberId).map(GroupMember::getRole).orElse(200);
                int newRole = GroupRoleCodec.fromImRoleOrDefault((String)role);
                this.groupProjection.onMemberRoleChanged(groupId, memberId, role);
                String memberBiz = toBusinessId(memberId);
                String operatorBiz = toBusinessId(operator);
                this.publish(groupId, "member_role_changed", operator, memberId == null ? List.of() : List.of(memberId), targets, GroupRealtimeDetailFactory.memberRoleChanged((String)memberBiz, (int)newRole, (int)previousRole, (String)operatorBiz, (Instant)Instant.now()));
                if (groupId != null && memberId != null && operator != null) {
                    if (newRole == 300 && previousRole != 300) {
                        this.systemNoticeService.recordAndPublish(groupId, GroupSystemNoticeType.grant_administrator, operatorBiz, memberBiz);
                    } else if (newRole == 200 && previousRole == 300) {
                        this.systemNoticeService.recordAndPublish(groupId, GroupSystemNoticeType.revoke_administrator, operatorBiz, memberBiz);
                    }
                }
            }
            if (hasMutedUntilKey) {
                long mutedUntilSec = parseMutedUntilSec(mutedUntilRaw);
                if (memberId != null) {
                    this.groupProjection.onMemberMutedUntilChanged(groupId, memberId, mutedUntilSec);
                }
                LinkedHashMap<String, Object> detail = new LinkedHashMap<String, Object>();
                if (memberId != null) {
                    detail.put("memberUserId", toBusinessId(memberId));
                }
                detail.put("mutedUntil", String.valueOf(Math.max(mutedUntilSec, 0L)));
                long nowSec = Instant.now().getEpochSecond();
                boolean stillMuted = mutedUntilSec > nowSec;
                this.publish(groupId, stillMuted ? "member_muted" : "member_unmuted", operator,
                    memberId == null ? List.of() : List.of(memberId), targets, detail);
            } else if (role == null) {
                // 无 Role、无 MutedUntil：保持旧行为视为解禁通知
                if (memberId != null) {
                    this.groupProjection.onMemberMutedUntilChanged(groupId, memberId, 0L);
                }
                LinkedHashMap<String, Object> detail = new LinkedHashMap<String, Object>();
                if (memberId != null) {
                    detail.put("memberUserId", toBusinessId(memberId));
                }
                this.publish(groupId, "member_unmuted", operator, memberId == null ? List.of() : List.of(memberId), targets, detail);
            }
        }
    }

    private static long parseMutedUntilSec(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void handleMemberField(Map<String, Object> body) {
        List list;
        String groupId = ImGroupRealtimeCallbackService.str(body.get("GroupId"));
        String operator = ImGroupRealtimeCallbackService.str(body.get("Operator_Account"));
        Object rawList = body.get("MemberList");
        if (!(rawList instanceof List) || (list = (List)rawList).isEmpty()) {
            return;
        }
        this.groupMemberCache.invalidate(groupId);
        List<String> targets = this.memberTargets(groupId, null);
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map m = (Map)item;
            String memberId = ImGroupRealtimeCallbackService.str(m.get("Member_Account"));
            String nameCard = ImGroupRealtimeCallbackService.str(m.get("NameCard"));
            LinkedHashMap<String, String> detail = new LinkedHashMap<String, String>();
            if (memberId != null) {
                detail.put("memberUserId", toBusinessId(memberId));
            }
            if (nameCard == null) continue;
            this.groupProjection.onMemberNameCardChanged(groupId, memberId, nameCard);
            this.publish(groupId, "member_profile_changed", operator, memberId == null ? List.of() : List.of(memberId), targets, GroupRealtimeDetailFactory.memberProfileChanged((String)toBusinessId(memberId), (String)nameCard, (Instant)Instant.now()));
        }
    }

    private Instant profileUpdatedAt(String groupId) {
        return this.groupProjection.findProfile(groupId).map(GroupProfile::getUpdatedAt).orElse(Instant.now());
    }

    private List<String> memberTargets(String groupId, List<String> extraUserIds) {
        List<String> members = this.imAdmin.listGroupMemberUserIds(groupId, 10000);
        return toBusinessIds(ImGroupRealtimeCallbackService.union(members, extraUserIds));
    }

    private String toBusinessId(String imOrAccount) {
        if (imOrAccount == null || imOrAccount.isBlank()) {
            return imOrAccount;
        }
        return this.imUserIdService.toBusinessForDisplay(imOrAccount.trim());
    }

    private List<String> toBusinessIds(List<String> imOrAccounts) {
        if (imOrAccounts == null || imOrAccounts.isEmpty()) {
            return List.of();
        }
        Map<String, String> mapped = this.imUserIdService.toBusinessForDisplayBatch(imOrAccounts);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : imOrAccounts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            out.add(mapped.getOrDefault(id, id));
        }
        return new ArrayList<>(out);
    }

    private void publish(String groupId, String action, String operatorUserId, List<String> memberUserIds, List<String> targetUserIds, Map<String, Object> detail) {
        Instant occurredAt = this.profileUpdatedAt(groupId);
        long occurredAtMs = occurredAt.toEpochMilli();
        Map enriched = GroupRealtimeDetailFactory.enrichWithOccurredAt(detail, (long)occurredAtMs);
        this.groupRealtime.publish(
            groupId,
            action,
            toBusinessId(operatorUserId),
            toBusinessIds(memberUserIds),
            toBusinessIds(targetUserIds),
            enriched,
            GroupChangeIdGenerator.newChangeEventId(),
            occurredAtMs,
            GroupTimelineRank.forAction((String)action));
        log.info("group realtime published groupId={} action={} targets={}", groupId, action, targetUserIds == null ? 0 : targetUserIds.size());
    }

    private static Set<String> resolveUpdatedFields(Map<String, Object> body) {
        LinkedHashSet<String> fields = new LinkedHashSet<String>();
        Object raw = body.get("UpdatedFields");
        if (raw instanceof List) {
            List list = (List)raw;
            for (Object item : list) {
                if (item == null || item.toString().isBlank()) continue;
                fields.add(item.toString().trim());
            }
        }
        if (fields.isEmpty()) {
            if (body.containsKey("Notification")) {
                fields.add("Notification");
            }
            if (body.containsKey("Name")) {
                fields.add("Name");
            }
            if (body.containsKey("FaceUrl")) {
                fields.add("FaceUrl");
            }
            if (body.containsKey("ShutUpAllMember")) {
                fields.add("ShutUpAllMember");
            }
            if (body.containsKey("ApplyJoinOption")) {
                fields.add("ApplyJoinOption");
            }
        }
        return fields;
    }

    private static List<String> extractMemberAccounts(Object rawList) {
        if (!(rawList instanceof List)) {
            return List.of();
        }
        List list = (List)rawList;
        ArrayList<String> out = new ArrayList<String>();
        for (Object item : list) {
            if (item instanceof Map) {
                Map m = (Map)item;
                String account = ImGroupRealtimeCallbackService.str(m.get("Member_Account"));
                if (account == null || account.isBlank()) continue;
                out.add(account.trim());
                continue;
            }
            if (item == null || item.toString().isBlank()) continue;
            out.add(item.toString().trim());
        }
        return out;
    }

    private static List<String> union(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (a != null) {
            set.addAll(a);
        }
        if (b != null) {
            set.addAll(b);
        }
        return new ArrayList<String>(set);
    }

    private static Map<String, Object> mapOf(String k1, Object v1) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        if (v1 != null) {
            m.put(k1, v1);
        }
        return m;
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            Map parsed = (Map)this.json.readValue(rawBody, Map.class);
            return parsed;
        }
        catch (JsonProcessingException e) {
            log.warn("group realtime callback invalid json: {}", (Object)e.getMessage());
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_JSON");
        }
    }

    private void validateSdkAppId(String sdkAppId) {
        if (sdkAppId == null || sdkAppId.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Set<String> allowed = this.allowedSdkAppIds();
        if (!allowed.isEmpty() && !allowed.contains(sdkAppId.trim())) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private Set<String> allowedSdkAppIds() {
        String csv = this.pushConfig.getAllowedSdkAppIds();
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        }
        int configured = this.settings.getInt("IM_SDK_APP_ID", 0);
        if (configured != 0) {
            return Set.of(String.valueOf(configured));
        }
        return Set.of();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String ... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            return value.trim();
        }
        return null;
    }
}
