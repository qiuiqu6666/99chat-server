package com.chat99.server.group;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

class GroupSystemNoticeDismissRepositoryImpl implements GroupSystemNoticeDismissRepositoryCustom {

    static final int INSERT_CHUNK = 500;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public int insertIgnoreBatch(String userId, List<String> noticeIds, Instant dismissedAt) {
        if (userId == null || userId.isBlank() || noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        Instant at = dismissedAt == null ? Instant.now() : dismissedAt;
        Timestamp ts = Timestamp.from(at);
        int inserted = 0;
        for (int from = 0; from < noticeIds.size(); from += INSERT_CHUNK) {
            int to = Math.min(from + INSERT_CHUNK, noticeIds.size());
            List<String> chunk = noticeIds.subList(from, to);
            StringBuilder sql = new StringBuilder(
                "INSERT IGNORE INTO group_system_notice_dismiss (user_id, notice_id, dismissed_at) VALUES ");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("(?,?,?)");
            }
            Query query = entityManager.createNativeQuery(sql.toString());
            int idx = 1;
            for (String noticeId : chunk) {
                query.setParameter(idx++, userId);
                query.setParameter(idx++, noticeId);
                query.setParameter(idx++, ts);
            }
            inserted += query.executeUpdate();
        }
        return inserted;
    }
}
