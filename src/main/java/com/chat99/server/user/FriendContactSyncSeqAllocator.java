package com.chat99.server.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 好友通讯录全局单调 Sync seq（MySQL LAST_INSERT_ID，多实例安全）。
 */
@Component
public class FriendContactSyncSeqAllocator {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long nextSeq() {
        entityManager.createNativeQuery(
                "UPDATE friend_contact_sync_seq SET next_val = LAST_INSERT_ID(next_val + 1) WHERE id = 1")
            .executeUpdate();
        Object raw = entityManager.createNativeQuery("SELECT LAST_INSERT_ID()")
            .getSingleResult();
        return ((Number) raw).longValue();
    }
}
