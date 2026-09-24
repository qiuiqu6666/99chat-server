package com.chat99.server.im.restqueue;

import com.chat99.server.group.GroupImSyncService;
import com.chat99.server.group.GroupJoinOption;
import com.chat99.server.group.GroupMembershipReconcileService;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImGroupFetchResult;
import com.chat99.server.im.ImGroupRoleCache;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class ImRestJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ImRestJobHandler.class);

    public enum Outcome {
        DONE,
        RETRY,
        DEAD
    }

    private final ImAdminClient im;
    private final GroupProjectionService projection;
    private final ImGroupRoleCache roleCache;
    private final ImRestRateLimiter rateLimiter;
    private final ImRestCircuitBreaker circuitBreaker;
    private final ImUserIdService imUserIdService;
    private final GroupImSyncService groupImSyncService;
    private final GroupMembershipReconcileService membershipReconcile;
    private final ObjectMapper json;

    public ImRestJobHandler(ImAdminClient im,
                            GroupProjectionService projection,
                            ImGroupRoleCache roleCache,
                            ImRestRateLimiter rateLimiter,
                            ImRestCircuitBreaker circuitBreaker,
                            ImUserIdService imUserIdService,
                            GroupImSyncService groupImSyncService,
                            GroupMembershipReconcileService membershipReconcile,
                            ObjectMapper json) {
        this.im = im;
        this.projection = projection;
        this.roleCache = roleCache;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.imUserIdService = imUserIdService;
        this.groupImSyncService = groupImSyncService;
        this.membershipReconcile = membershipReconcile;
        this.json = json;
    }

    public Outcome handle(ImRestJob job) {
        if (job == null || job.type() == null) {
            return Outcome.DEAD;
        }
        return switch (job.type()) {
            case REFRESH_ROLE -> refreshRole(job);
            case HYDRATE_GROUP -> hydrate(job);
            case SYNC_USER_JOINED -> syncJoined(job);
            case VERIFY_GROUP_EXISTS -> verifyExists(job);
            case FRIEND_ADD_BOTH -> friendAddBoth(job);
            case FRIEND_DELETE_BOTH -> friendDeleteBoth(job);
            case FRIEND_REMARK_UPDATE -> friendRemarkUpdate(job);
            case GROUP_MODIFY_BASE_INFO -> groupModifyBaseInfo(job);
            case GROUP_MODIFY_FACE_URL -> groupModifyFaceUrl(job);
            case GROUP_MODIFY_JOIN_OPTIONS -> groupModifyJoinOptions(job);
            case GROUP_ADD_MEMBERS -> groupAddMembers(job);
            case GROUP_DELETE_MEMBERS -> groupDeleteMembers(job);
            case GROUP_DESTROY -> groupDestroy(job);
            case RECONCILE_GROUP_MEMBERS -> reconcileGroupMembers(job);
            case RECONCILE_GROUP_USERS -> reconcileGroupUsers(job);
        };
    }

    private Outcome friendAddBoth(ImRestJob job) {
        if (blank(job.userId()) || blank(job.peerUserId())) {
            return Outcome.DEAD;
        }
        if (!acquire("sns-friend-add")) {
            return Outcome.RETRY;
        }
        im.addFriendBoth(job.userId(), job.peerUserId(), "AddSource_Type_Server", null);
        return Outcome.DONE;
    }

    private Outcome friendDeleteBoth(ImRestJob job) {
        if (blank(job.userId()) || blank(job.peerUserId())) {
            return Outcome.DEAD;
        }
        if (!acquire("sns-friend-delete")) {
            return Outcome.RETRY;
        }
        im.deleteFriendBoth(job.userId(), job.peerUserId());
        return Outcome.DONE;
    }

    private Outcome friendRemarkUpdate(ImRestJob job) {
        if (blank(job.userId()) || blank(job.peerUserId())) {
            return Outcome.DEAD;
        }
        if (!acquire("sns-friend-update")) {
            return Outcome.RETRY;
        }
        im.updateFriendRemark(job.userId(), job.peerUserId(), job.remark() == null ? "" : job.remark());
        return Outcome.DONE;
    }

    private Outcome groupModifyBaseInfo(ImRestJob job) {
        if (blank(job.groupId()) || blank(job.stringValue())) {
            return Outcome.DEAD;
        }
        if (!acquire("modify-group-base")) {
            return Outcome.RETRY;
        }
        try {
            JsonNode node = json.readTree(job.stringValue());
            String name = textOrNull(node, "name");
            String notification = node.has("notification") ? node.get("notification").asText("") : null;
            boolean hasName = name != null && !name.isBlank();
            boolean hasNotice = node.has("notification");
            if (!hasName && !hasNotice) {
                return Outcome.DEAD;
            }
            im.modifyGroupBaseInfo(job.groupId(), hasName ? name : null, hasNotice ? notification : null);
            return Outcome.DONE;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("group modify base_info bad payload groupId={} err={}", job.groupId(), e.getMessage());
            return Outcome.DEAD;
        }
    }

    private Outcome groupModifyFaceUrl(ImRestJob job) {
        if (blank(job.groupId())) {
            return Outcome.DEAD;
        }
        if (!acquire("modify-group-base")) {
            return Outcome.RETRY;
        }
        im.modifyGroupFaceUrl(job.groupId(), job.stringValue() == null ? "" : job.stringValue());
        return Outcome.DONE;
    }

    private Outcome groupModifyJoinOptions(ImRestJob job) {
        if (blank(job.groupId()) || blank(job.stringValue())) {
            return Outcome.DEAD;
        }
        if (!acquire("modify-group-base")) {
            return Outcome.RETRY;
        }
        try {
            JsonNode node = json.readTree(job.stringValue());
            GroupJoinOption apply = parseOption(textOrNull(node, "applyJoinOption"));
            GroupJoinOption invite = parseOption(textOrNull(node, "inviteJoinOption"));
            if (apply == null && invite == null) {
                return Outcome.DEAD;
            }
            im.modifyGroupJoinOptions(job.groupId(), apply, invite);
            return Outcome.DONE;
        } catch (Exception e) {
            log.warn("group modify join_options bad payload groupId={} err={}", job.groupId(), e.getMessage());
            return Outcome.DEAD;
        }
    }

    private Outcome groupAddMembers(ImRestJob job) {
        if (blank(job.groupId()) || job.memberUserIds() == null || job.memberUserIds().isEmpty()) {
            return Outcome.DEAD;
        }
        if (!acquire("add-group-member")) {
            return Outcome.RETRY;
        }
        im.addGroupMembers(job.groupId(), toImAccounts(job.memberUserIds()), false);
        groupImSyncService.enqueueMembershipReconcile(job.groupId(), job.memberUserIds(), "im_job_add");
        return Outcome.DONE;
    }

    private Outcome groupDeleteMembers(ImRestJob job) {
        if (blank(job.groupId()) || job.memberUserIds() == null || job.memberUserIds().isEmpty()) {
            return Outcome.DEAD;
        }
        if (!acquire("delete-group-member")) {
            return Outcome.RETRY;
        }
        im.deleteGroupMembers(job.groupId(), toImAccounts(job.memberUserIds()), false);
        groupImSyncService.enqueueMembershipReconcile(job.groupId(), job.memberUserIds(), "im_job_delete");
        return Outcome.DONE;
    }

    private Outcome groupDestroy(ImRestJob job) {
        if (blank(job.groupId())) {
            return Outcome.DEAD;
        }
        if (!acquire("destroy-group")) {
            return Outcome.RETRY;
        }
        im.destroyGroup(job.groupId());
        return Outcome.DONE;
    }

    private Outcome refreshRole(ImRestJob job) {
        if (blank(job.groupId()) || blank(job.userId())) {
            return Outcome.DEAD;
        }
        if (!acquire("get-role-in-group")) {
            return Outcome.RETRY;
        }
        String imAccount = imUserIdService.toImAccount(job.userId());
        try {
            membershipReconcile.reconcileUsers(job.groupId(), List.of(imAccount));
            roleCache.evict(job.groupId(), imAccount);
            return Outcome.DONE;
        } catch (Exception e) {
            log.warn("refresh role reconcile failed groupId={} userId={} err={}",
                job.groupId(), imAccount, e.getMessage());
            return Outcome.RETRY;
        }
    }

    private Outcome reconcileGroupMembers(ImRestJob job) {
        if (blank(job.groupId())) {
            return Outcome.DEAD;
        }
        if (!acquire("get-role-in-group")) {
            return Outcome.RETRY;
        }
        try {
            membershipReconcile.reconcile(job.groupId());
            return Outcome.DONE;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.CONFLICT) {
                return Outcome.DONE;
            }
            log.warn("reconcile group members rejected groupId={} err={}", job.groupId(), e.getReason());
            return Outcome.RETRY;
        } catch (Exception e) {
            log.warn("reconcile group members failed groupId={} err={}", job.groupId(), e.getMessage());
            return Outcome.RETRY;
        }
    }

    private Outcome reconcileGroupUsers(ImRestJob job) {
        if (blank(job.groupId()) || job.memberUserIds() == null || job.memberUserIds().isEmpty()) {
            return Outcome.DEAD;
        }
        if (!acquire("get-role-in-group")) {
            return Outcome.RETRY;
        }
        try {
            membershipReconcile.reconcileUsers(job.groupId(), job.memberUserIds());
            return Outcome.DONE;
        } catch (Exception e) {
            log.warn("reconcile group users failed groupId={} err={}", job.groupId(), e.getMessage());
            return Outcome.RETRY;
        }
    }

    private Outcome hydrate(ImRestJob job) {
        if (blank(job.groupId())) {
            return Outcome.DEAD;
        }
        if (!acquire("get-group-info")) {
            return Outcome.RETRY;
        }
        projection.hydrateGroupFromIm(job.groupId(), job.userId());
        return Outcome.DONE;
    }

    private Outcome syncJoined(ImRestJob job) {
        if (blank(job.userId())) {
            return Outcome.DEAD;
        }
        if (!acquire("get-joined-group-list")) {
            return Outcome.RETRY;
        }
        projection.syncJoinedGroupsFromIm(job.userId());
        return Outcome.DONE;
    }

    private Outcome verifyExists(ImRestJob job) {
        if (blank(job.groupId())) {
            return Outcome.DEAD;
        }
        if (!acquire("get-group-info")) {
            return Outcome.RETRY;
        }
        ImGroupFetchResult result = im.fetchGroupAdminInfoResult(job.groupId());
        if (result.isDefinitelyGone()) {
            projection.onGroupDismissed(job.groupId(), "verify_im_gone");
            log.info("verify group gone dismissed groupId={} code={}", job.groupId(), result.errorCode());
            return Outcome.DONE;
        }
        if (result.isTransientFailure()) {
            if (result.status() == ImGroupFetchResult.Status.RATE_LIMITED) {
                circuitBreaker.open("get-group-info");
            }
            return Outcome.RETRY;
        }
        if (projection.isLocallyDismissed(job.groupId())) {
            GroupProjectionService.RestoreResult restored =
                projection.restoreDismissedGroupFromImIfAlive(job.groupId());
            log.info("verify group alive restore groupId={} restored={} reason={}",
                job.groupId(), restored.restored(), restored.reason());
        }
        return Outcome.DONE;
    }

    private List<String> toImAccounts(List<String> businessUserIds) {
        List<String> out = new ArrayList<>(businessUserIds.size());
        for (String id : businessUserIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            out.add(imUserIdService.toIm(id.trim()));
        }
        return out;
    }

    private static GroupJoinOption parseOption(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return GroupJoinOption.valueOf(raw.trim());
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private boolean acquire(String apiKey) {
        if (circuitBreaker.isOpen(apiKey)) {
            return false;
        }
        return rateLimiter.acquire(apiKey, 2_000L);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
