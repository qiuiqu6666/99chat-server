package com.chat99.server.wallet;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransferRepository extends JpaRepository<WalletTransfer, Long>,
    JpaSpecificationExecutor<WalletTransfer> {

    Optional<WalletTransfer> findByFromUserIdAndClientOrderId(String fromUserId, String clientOrderId);

    Optional<WalletTransfer> findByToUserIdAndClientOrderId(String toUserId, String clientOrderId);

    java.util.List<WalletTransfer> findByClientOrderId(String clientOrderId);

    Page<WalletTransfer> findByFromUserIdOrderByCreatedAtDesc(String fromUserId, Pageable pageable);

    Page<WalletTransfer> findByToUserIdOrderByCreatedAtDesc(String toUserId, Pageable pageable);

    @Query("SELECT t FROM WalletTransfer t WHERE t.fromUserId = :userId OR t.toUserId = :userId "
        + "ORDER BY t.createdAt DESC")
    Page<WalletTransfer> findByUserInvolvedOrderByCreatedAtDesc(@Param("userId") String userId, Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(t.amount), 0)
        FROM WalletTransfer t
        WHERE t.status = com.chat99.server.wallet.WalletTransferStatus.COMPLETED
          AND t.createdAt >= :start AND t.createdAt < :end
        """)
    long sumCompletedAmountBetween(@Param("start") Instant start, @Param("end") Instant end);
}
