package com.chat99.server.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 好友通讯录域全局单调 revision（每次写 friend_contact_change 时 +1）。
 * 配合 {@code snapshotRevision}，保证客户端分页期间视图一致。
 * <p>实现沿用 {@code LAST_INSERT_ID} 技巧，多实例安全。
 */
@Component
public class FriendContactRevisionSeqAllocator {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long next() {
        entityManager.createNativeQuery(
                "UPDATE friend_contact_revision_seq SET next_val = LAST_INSERT_ID(next_val + 1) WHERE id = 1")
            .executeUpdate();
        Object raw = entityManager.createNativeQuery("SELECT LAST_INSERT_ID()")
            .getSingleResult();
        return ((Number) raw).longValue();
    }
}
