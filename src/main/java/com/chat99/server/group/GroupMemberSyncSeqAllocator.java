package com.chat99.server.group;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全局单调成员 Sync seq 分配（MySQL LAST_INSERT_ID 技巧，多实例安全）。
 */
@Component
public class GroupMemberSyncSeqAllocator {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long nextSeq() {
        entityManager.createNativeQuery(
                "UPDATE group_member_sync_seq SET next_val = LAST_INSERT_ID(next_val + 1) WHERE id = 1")
            .executeUpdate();
        Object raw = entityManager.createNativeQuery("SELECT LAST_INSERT_ID()")
            .getSingleResult();
        return ((Number) raw).longValue();
    }
}
