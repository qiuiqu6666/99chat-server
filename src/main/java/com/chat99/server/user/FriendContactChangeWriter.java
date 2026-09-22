package com.chat99.server.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FriendContactChangeWriter {

    private static final Logger log = LoggerFactory.getLogger(FriendContactChangeWriter.class);

    public static final String TYPE_CONTACT_CREATED = "CONTACT_CREATED";
    public static final String TYPE_CONTACT_DELETED = "CONTACT_DELETED";
    public static final String TYPE_CONTACT_UPDATED = "CONTACT_UPDATED";
    public static final String TYPE_CONTACT_REMARK_UPDATED = "CONTACT_REMARK_UPDATED";
    public static final String TYPE_CONTACT_PROFILE_UPDATED = "CONTACT_PROFILE_UPDATED";

    public record WriteResult(long seq, long revision, String eventId) {}

    private final FriendContactSyncSeqAllocator seqAllocator;
    private final FriendContactRevisionSeqAllocator revisionAllocator;
    private final FriendContactChangeRepository changeRepository;
    private final UserFriendRepository friendRepository;
    private final ObjectMapper json;

    public FriendContactChangeWriter(FriendContactSyncSeqAllocator seqAllocator,
                                     FriendContactRevisionSeqAllocator revisionAllocator,
                                     FriendContactChangeRepository changeRepository,
                                     UserFriendRepository friendRepository,
                                     ObjectMapper json) {
        this.seqAllocator = seqAllocator;
        this.revisionAllocator = revisionAllocator;
        this.changeRepository = changeRepository;
        this.friendRepository = friendRepository;
        this.json = json;
    }

    /**
     * 写一条好友变更。同时分配：
     * <ul>
     *   <li>{@code seq} — 全局单调，写入顺序（增量游标）</li>
     *   <li>{@code revision} — 域全局单调（snapshotRevision 一致性保证）</li>
     *   <li>{@code itemVersion} — {@code peerUserId} 单条实体当前最新 +1（snapshot 去重）</li>
     *   <li>{@code eventId} — UUID（增量幂等去重）</li>
     * </ul>
     *
     * @return {@link WriteResult} seq / revision / eventId；任一字段为 0 / null 表示失败
     */
    @Transactional
    public WriteResult write(String accountId,
                             String eventType,
                             String peerUserId,
                             Map<String, Object> payload,
                             boolean deleted) {
        if (accountId == null || accountId.isBlank()
            || eventType == null || eventType.isBlank()
            || peerUserId == null || peerUserId.isBlank()) {
            return new WriteResult(0L, 0L, null);
        }
        long seq = seqAllocator.nextSeq();
        long revision = revisionAllocator.next();
        String normalizedAccountId = accountId.trim();
        String normalizedPeerUserId = peerUserId.trim();
        long itemVersion = nextItemVersion(normalizedAccountId, normalizedPeerUserId);
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        FriendContactChange row = new FriendContactChange();
        row.setSeq(seq);
        row.setEventId(eventId);
        row.setItemVersion(itemVersion);
        row.setRevision(revision);
        row.setDeleted(deleted);
        row.setAccountId(normalizedAccountId);
        row.setEventType(eventType);
        row.setPeerUserId(normalizedPeerUserId);
        row.setPayloadJson(writeJson(payload));
        row.setCreatedAt(System.currentTimeMillis());
        changeRepository.save(row);
        return new WriteResult(seq, revision, eventId);
    }

    /** 便捷重载：默认 deleted=false */
    public WriteResult write(String accountId, String eventType, String peerUserId, Map<String, Object> payload) {
        return write(accountId, eventType, peerUserId, payload, false);
    }

    /**
     * 关系行和事件流共用同一个单调 itemVersion，避免全量快照覆盖较新的增量事件。
     */
    private long nextItemVersion(String accountId, String peerUserId) {
        long eventVersion = java.util.Optional.ofNullable(
            changeRepository.findMaxItemVersionForAccountAndPeer(accountId, peerUserId)).orElse(0L);
        UserFriend relation = friendRepository.findIncludingDeletedByUserIdAndFriendUserId(accountId, peerUserId)
            .orElse(null);
        long relationVersion = relation == null ? 0L : relation.getItemVersion();
        long nextVersion = Math.max(eventVersion, relationVersion) + 1L;
        if (relation != null) {
            relation.setItemVersion(nextVersion);
            friendRepository.save(relation);
        }
        return nextVersion;
    }

    public static Map<String, Object> snapshotPayload(
        String peerNickname,
        String peerAvatarUrl,
        String remark,
        Boolean inMyFriendList,
        Boolean isFriend,
        Boolean peerDeletedMe,
        Boolean canMessage,
        Long lastActiveAt,
        String lastActiveVisibility,
        String tcpAction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (peerNickname != null) payload.put("peerNickname", peerNickname);
        if (peerAvatarUrl != null) payload.put("peerAvatarUrl", peerAvatarUrl);
        if (remark != null) payload.put("remark", remark);
        if (inMyFriendList != null) payload.put("inMyFriendList", inMyFriendList);
        if (isFriend != null) payload.put("isFriend", isFriend);
        if (peerDeletedMe != null) payload.put("peerDeletedMe", peerDeletedMe);
        if (canMessage != null) payload.put("canMessage", canMessage);
        payload.put("lastActiveAt", lastActiveAt);
        if (lastActiveVisibility != null) payload.put("lastActiveVisibility", lastActiveVisibility);
        if (tcpAction != null) payload.put("tcpAction", tcpAction);
        return payload;
    }

    private String writeJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("friend contact change payload serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
