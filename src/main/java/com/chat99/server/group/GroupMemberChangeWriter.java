package com.chat99.server.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GroupMemberChangeWriter {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberChangeWriter.class);

    public static final String TYPE_MEMBER_UPSERTED = "MEMBER_UPSERTED";
    public static final String TYPE_MEMBER_REMOVED = "MEMBER_REMOVED";

    public record WriteResult(long seq, long revision, String eventId) {}

    private final GroupMemberSyncSeqAllocator seqAllocator;
    private final GroupMemberRevisionSeqAllocator revisionAllocator;
    private final GroupMemberChangeRepository changeRepository;
    private final ObjectMapper json;

    public GroupMemberChangeWriter(GroupMemberSyncSeqAllocator seqAllocator,
                                   GroupMemberRevisionSeqAllocator revisionAllocator,
                                   GroupMemberChangeRepository changeRepository,
                                   ObjectMapper json) {
        this.seqAllocator = seqAllocator;
        this.revisionAllocator = revisionAllocator;
        this.changeRepository = changeRepository;
        this.json = json;
    }

    @Transactional
    public WriteResult writeUpserted(String groupId, String userId, int memberCount, Map<String, Object> payload) {
        return persist(groupId, TYPE_MEMBER_UPSERTED, userId, memberCount, payload, false);
    }

    @Transactional
    public WriteResult writeRemoved(String groupId, String userId, int memberCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        return persist(groupId, TYPE_MEMBER_REMOVED, userId, memberCount, payload, true);
    }

    @Transactional
    public long writeUpsertedBatch(String groupId, List<String> userIds, int memberCount,
                                   Map<String, Map<String, Object>> payloadsByUser) {
        long maxSeq = 0L;
        if (userIds == null || userIds.isEmpty()) return maxSeq;
        for (String userId : userIds) {
            Map<String, Object> payload = payloadsByUser == null ? null : payloadsByUser.get(userId);
            long seq = writeUpserted(groupId, userId, memberCount, payload).seq();
            if (seq > maxSeq) maxSeq = seq;
        }
        return maxSeq;
    }

    @Transactional
    public long writeRemovedBatch(String groupId, List<String> userIds, int memberCount) {
        long maxSeq = 0L;
        if (userIds == null || userIds.isEmpty()) return maxSeq;
        for (String userId : userIds) {
            long seq = writeRemoved(groupId, userId, memberCount).seq();
            if (seq > maxSeq) maxSeq = seq;
        }
        return maxSeq;
    }

    private WriteResult persist(String groupId, String eventType, String userId, int memberCount,
                                Map<String, Object> payload, boolean deleted) {
        if (groupId == null || groupId.isBlank()
            || userId == null || userId.isBlank()
            || eventType == null || eventType.isBlank()) {
            return new WriteResult(0L, 0L, null);
        }
        long seq = seqAllocator.nextSeq();
        long revision = revisionAllocator.next();
        long itemVersion = nextItemVersion(groupId.trim(), userId.trim());
        String eventId = "gmevt_" + UUID.randomUUID().toString().replace("-", "");
        GroupMemberChange row = new GroupMemberChange();
        row.setSeq(seq);
        row.setEventId(eventId);
        row.setItemVersion(itemVersion);
        row.setRevision(revision);
        row.setDeleted(deleted);
        row.setGroupId(groupId.trim());
        row.setEventType(eventType);
        row.setUserId(userId.trim());
        row.setMemberCount(Math.max(memberCount, 0));
        row.setPayloadJson(writeJson(payload));
        row.setCreatedAt(System.currentTimeMillis());
        changeRepository.save(row);
        return new WriteResult(seq, revision, eventId);
    }

    private long nextItemVersion(String groupId, String userId) {
        long max = 0L;
        try {
            for (GroupMemberChange row : changeRepository.findForGroupSinceSeq(
                    groupId, 0L, org.springframework.data.domain.PageRequest.of(0, 1000))) {
                if (userId.equals(row.getUserId()) && row.getItemVersion() > max) {
                    max = row.getItemVersion();
                }
            }
        } catch (Exception e) {
            log.warn("nextItemVersion scan failed group={} user={}: {}",
                groupId, userId, e.getMessage());
        }
        return max + 1L;
    }

    private String writeJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("group member change payload serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
