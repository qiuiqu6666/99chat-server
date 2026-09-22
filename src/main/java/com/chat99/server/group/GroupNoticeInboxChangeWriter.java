package com.chat99.server.group;

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
public class GroupNoticeInboxChangeWriter {

    private static final Logger log = LoggerFactory.getLogger(GroupNoticeInboxChangeWriter.class);

    public static final String TYPE_NOTICE_UPSERTED = "NOTICE_UPSERTED";
    public static final String TYPE_NOTICE_DELETED = "NOTICE_DELETED";
    public static final String TYPE_READ_WATERMARK = "READ_WATERMARK";

    public record WriteResult(long seq, long revision, String eventId) {}

    private final GroupNoticeInboxSeqAllocator seqAllocator;
    private final GroupNoticeInboxRevisionSeqAllocator revisionAllocator;
    private final GroupNoticeInboxChangeRepository changeRepository;
    private final ObjectMapper json;

    public GroupNoticeInboxChangeWriter(GroupNoticeInboxSeqAllocator seqAllocator,
                                        GroupNoticeInboxRevisionSeqAllocator revisionAllocator,
                                        GroupNoticeInboxChangeRepository changeRepository,
                                        ObjectMapper json) {
        this.seqAllocator = seqAllocator;
        this.revisionAllocator = revisionAllocator;
        this.changeRepository = changeRepository;
        this.json = json;
    }

    @Transactional
    public WriteResult writeUpserted(String userId, String noticeId, Map<String, Object> payload) {
        return persist(userId, TYPE_NOTICE_UPSERTED, noticeId, payload, false);
    }

    @Transactional
    public WriteResult writeDeleted(String userId, String noticeId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeId", noticeId);
        return persist(userId, TYPE_NOTICE_DELETED, noticeId, payload, true);
    }

    @Transactional
    public long writeReadWatermark(String userId, long lastReadAtMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lastReadAtMs", lastReadAtMs);
        // 返回 long 兼容老调用
        return persist(userId, TYPE_READ_WATERMARK, null, payload, false).seq();
    }

    private WriteResult persist(String userId, String eventType, String noticeId, Map<String, Object> payload, boolean deleted) {
        if (userId == null || userId.isBlank() || eventType == null || eventType.isBlank()) {
            return new WriteResult(0L, 0L, null);
        }
        long seq = seqAllocator.nextSeq();
        long revision = revisionAllocator.next();
        long itemVersion = nextItemVersion(userId.trim(), noticeId);
        String eventId = "gnievt_" + UUID.randomUUID().toString().replace("-", "");
        GroupNoticeInboxChange row = new GroupNoticeInboxChange();
        row.setSeq(seq);
        row.setEventId(eventId);
        row.setItemVersion(itemVersion);
        row.setRevision(revision);
        row.setDeleted(deleted);
        row.setUserId(userId.trim());
        row.setEventType(eventType);
        row.setNoticeId(noticeId);
        row.setPayloadJson(writeJson(payload));
        row.setCreatedAt(System.currentTimeMillis());
        changeRepository.save(row);
        return new WriteResult(seq, revision, eventId);
    }

    private long nextItemVersion(String userId, String noticeId) {
        long max = 0L;
        try {
            for (GroupNoticeInboxChange row : changeRepository.findAllByUserIdOrderBySeqAsc(userId)) {
                if (noticeId != null && noticeId.equals(row.getNoticeId())
                    && row.getItemVersion() > max) {
                    max = row.getItemVersion();
                }
            }
        } catch (Exception e) {
            log.warn("nextItemVersion scan failed user={} notice={}: {}",
                userId, noticeId, e.getMessage());
        }
        return max + 1L;
    }

    private String writeJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("group notice inbox payload serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
