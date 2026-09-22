package com.chat99.server.group;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

class GroupJoinApplicationDismissRepositoryImpl implements GroupJoinApplicationDismissRepositoryCustom {

    static final int INSERT_CHUNK = 500;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public int insertIgnoreBatch(String userId, List<Long> applicationIds, Instant dismissedAt) {
        if (userId == null || userId.isBlank() || applicationIds == null || applicationIds.isEmpty()) {
            return 0;
        }
        Instant at = dismissedAt == null ? Instant.now() : dismissedAt;
        Timestamp ts = Timestamp.from(at);
        int inserted = 0;
        for (int from = 0; from < applicationIds.size(); from += INSERT_CHUNK) {
            int to = Math.min(from + INSERT_CHUNK, applicationIds.size());
            List<Long> chunk = applicationIds.subList(from, to);
            StringBuilder sql = new StringBuilder(
                "INSERT IGNORE INTO group_join_application_dismiss (user_id, application_id, dismissed_at) VALUES ");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("(?,?,?)");
            }
            Query query = entityManager.createNativeQuery(sql.toString());
            int idx = 1;
            for (Long applicationId : chunk) {
                query.setParameter(idx++, userId);
                query.setParameter(idx++, applicationId);
                query.setParameter(idx++, ts);
            }
            inserted += query.executeUpdate();
        }
        return inserted;
    }
}
