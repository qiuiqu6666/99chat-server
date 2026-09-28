package com.chat99.server.wallet;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface WalletCardOutboxRepository extends JpaRepository<WalletCardOutbox, String> {
    Optional<WalletCardOutbox> findBySenderIdAndClientId(String senderId, String clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from WalletCardOutbox o where o.id = :id")
    Optional<WalletCardOutbox> lock(@Param("id") String id);

    @Query("select o.id from WalletCardOutbox o where o.state in ('PENDING', 'SENDING', 'REVIEW', 'RECONCILING') "
        + "and o.nextAttemptAt <= :now order by o.nextAttemptAt, o.id")
    List<String> due(@Param("now") Instant now, Pageable page);

    @Query("select o.id from WalletCardOutbox o where o.state = 'SENT' and o.id like 'rp:%' "
        + "and o.nextSyncAt <= :now order by o.nextSyncAt, o.id")
    List<String> syncDue(@Param("now") Instant now, Pageable page);

    @Query("select o from WalletCardOutbox o where o.groupMessage = :isGroup and "
        + "((:isGroup = true and o.targetId = :target) or (:isGroup = false and "
        + "((o.senderId = :viewer and o.targetId = :target) or (o.senderId = :target and o.targetId = :viewer)))) "
        + "and (o.createdAt >= :joined or o.senderId = :viewer) "
        + "and (o.createdAt < :before or (o.createdAt = :before and o.id < :beforeId)) "
        + "order by o.createdAt desc, o.id desc")
    List<WalletCardOutbox> conversationCards(@Param("viewer") String viewer, @Param("target") String target,
        @Param("isGroup") boolean group, @Param("joined") Instant joined,
        @Param("before") Instant before, @Param("beforeId") String beforeId, Pageable page);
}
