package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 自建群写后同步腾讯 IM。加减成员与本地同一请求（失败回滚）；资料/解散仍 afterCommit，失败入 Kafka。
 */
@Service
public class GroupImSyncService {

    private static final Logger log = LoggerFactory.getLogger(GroupImSyncService.class);

    private final ImAdminClient im;
    private final ImUserIdService imUserIdService;
    private final ObjectProvider<ImRestQueuePublisher> queuePublisher;
    private final ObjectMapper json;

    public GroupImSyncService(ImAdminClient im,
                              ImUserIdService imUserIdService,
                              ObjectProvider<ImRestQueuePublisher> queuePublisher,
                              ObjectMapper json) {
        this.im = im;
        this.imUserIdService = imUserIdService;
        this.queuePublisher = queuePublisher;
        this.json = json;
    }

    public void syncGroupBaseInfo(String groupId, String nameOrNull, String noticeOrNull) {
        runAfterCommit(() -> trySyncGroupBaseInfo(groupId, nameOrNull, noticeOrNull));
    }

    public void syncGroupFaceUrl(String groupId, String faceUrl) {
        runAfterCommit(() -> trySyncGroupFaceUrl(groupId, faceUrl));
    }

    public void syncJoinOptions(String groupId, GroupJoinOption apply, GroupJoinOption invite) {
        runAfterCommit(() -> trySyncJoinOptions(groupId, apply, invite));
    }

    public void syncAddMembers(String groupId, List<String> memberUserIds) {
        trySyncAddMembers(groupId, memberUserIds);
    }

    public void syncDeleteMembers(String groupId, List<String> memberUserIds) {
        trySyncDeleteMembers(groupId, memberUserIds);
    }

    public void syncDestroyGroup(String groupId) {
        runAfterCommit(() -> trySyncDestroyGroup(groupId));
    }

    void trySyncGroupBaseInfo(String groupId, String nameOrNull, String noticeOrNull) {
        String gid = trim(groupId);
        if (blank(gid)) {
            return;
        }
        String payload = baseInfoJson(nameOrNull, noticeOrNull);
        try {
            im.modifyGroupBaseInfo(gid, blank(nameOrNull) ? null : nameOrNull.trim(), noticeOrNull);
            log.info("group im sync base_info ok groupId={}", gid);
        } catch (Exception e) {
            log.warn("group im sync base_info failed groupId={} err={}", gid, e.getMessage());
            enqueue(p -> p.enqueueGroupModifyBaseInfo(gid, payload, summarize(e)));
        }
    }

    void trySyncGroupFaceUrl(String groupId, String faceUrl) {
        String gid = trim(groupId);
        if (blank(gid)) {
            return;
        }
        String url = faceUrl == null ? "" : faceUrl;
        try {
            im.modifyGroupFaceUrl(gid, url);
            log.info("group im sync face_url ok groupId={}", gid);
        } catch (Exception e) {
            log.warn("group im sync face_url failed groupId={} err={}", gid, e.getMessage());
            enqueue(p -> p.enqueueGroupModifyFaceUrl(gid, url, summarize(e)));
        }
    }

    void trySyncJoinOptions(String groupId, GroupJoinOption apply, GroupJoinOption invite) {
        String gid = trim(groupId);
        if (blank(gid)) {
            return;
        }
        String payload = joinOptionsJson(apply, invite);
        try {
            im.modifyGroupJoinOptions(gid, apply, invite);
            log.info("group im sync join_options ok groupId={}", gid);
        } catch (Exception e) {
            log.warn("group im sync join_options failed groupId={} err={}", gid, e.getMessage());
            enqueue(p -> p.enqueueGroupModifyJoinOptions(gid, payload, summarize(e)));
        }
    }

    void trySyncAddMembers(String groupId, List<String> memberUserIds) {
        String gid = trim(groupId);
        List<String> members = normalizeMembers(memberUserIds);
        if (blank(gid) || members.isEmpty()) {
            return;
        }
        List<String> imAccounts = toImAccounts(members);
        im.addGroupMembers(gid, imAccounts, false);
        log.info("group im sync add_members ok groupId={} count={}", gid, members.size());
    }

    void trySyncDeleteMembers(String groupId, List<String> memberUserIds) {
        String gid = trim(groupId);
        List<String> members = normalizeMembers(memberUserIds);
        if (blank(gid) || members.isEmpty()) {
            return;
        }
        List<String> imAccounts = toImAccounts(members);
        im.deleteGroupMembers(gid, imAccounts, false);
        log.info("group im sync delete_members ok groupId={} count={}", gid, members.size());
    }

    void trySyncDestroyGroup(String groupId) {
        String gid = trim(groupId);
        if (blank(gid)) {
            return;
        }
        try {
            im.destroyGroup(gid);
            log.info("group im sync destroy ok groupId={}", gid);
        } catch (Exception e) {
            log.warn("group im sync destroy failed groupId={} err={}", gid, e.getMessage());
            enqueue(p -> p.enqueueGroupDestroy(gid, summarize(e)));
        }
    }

    void runAfterCommit(Runnable action) {
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

    private List<String> toImAccounts(List<String> businessUserIds) {
        List<String> out = new ArrayList<>(businessUserIds.size());
        for (String id : businessUserIds) {
            out.add(imUserIdService.toIm(id));
        }
        return out;
    }

    private String baseInfoJson(String nameOrNull, String noticeOrNull) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (nameOrNull != null && !nameOrNull.isBlank()) {
            body.put("name", nameOrNull.trim());
        }
        if (noticeOrNull != null) {
            body.put("notification", noticeOrNull);
        }
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String joinOptionsJson(GroupJoinOption apply, GroupJoinOption invite) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (apply != null) {
            body.put("applyJoinOption", apply.name());
        }
        if (invite != null) {
            body.put("inviteJoinOption", invite.name());
        }
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** IM 加减成员成功后再对账角色，避免本地投影和 IM 成员状态分叉。 */
    public void enqueueMembershipReconcile(String groupId, List<String> userIds, String reason) {
        List<String> members = normalizeMembers(userIds);
        if (blank(groupId) || members.isEmpty()) {
            return;
        }
        ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
        if (publisher == null) {
            return;
        }
        try {
            for (String userId : members) {
                publisher.enqueueRefreshRole(groupId, userId, reason);
                publisher.enqueueSyncUserJoined(userId, reason);
            }
        } catch (Exception e) {
            log.warn("group im reconcile enqueue failed groupId={} reason={} err={}",
                groupId, reason, e.getMessage());
        }
    }

    private void enqueue(java.util.function.Consumer<ImRestQueuePublisher> action) {
        ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
        if (publisher == null) {
            log.error("group im sync enqueue skipped (queue unavailable)");
            return;
        }
        try {
            action.accept(publisher);
        } catch (Exception e) {
            log.error("group im sync enqueue failed err={}", e.getMessage());
        }
    }

    private static List<String> normalizeMembers(List<String> memberUserIds) {
        if (memberUserIds == null || memberUserIds.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : memberUserIds) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }

    private static String summarize(Exception e) {
        if (e instanceof ImRestException ire) {
            return ire.getMessage() + ":" + ire.imErrorCode();
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
