package com.chat99.server.group;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 群通知收件箱变更域全局单调 revision（每次写 group_notice_inbox_change 时 +1）。
 */
@Component
public class GroupNoticeInboxRevisionSeqAllocator {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long next() {
        entityManager.createNativeQuery(
                "UPDATE group_notice_inbox_revision_seq SET next_val = LAST_INSERT_ID(next_val + 1) WHERE id = 1")
            .executeUpdate();
        Object raw = entityManager.createNativeQuery("SELECT LAST_INSERT_ID()")
            .getSingleResult();
        return ((Number) raw).longValue();
    }
}
