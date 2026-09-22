package com.chat99.server.wallet;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletWithdrawalRepository extends JpaRepository<WalletWithdrawal, Long>,
    JpaSpecificationExecutor<WalletWithdrawal> {

    Optional<WalletWithdrawal> findByUserIdAndClientOrderId(String userId, String clientOrderId);

    List<WalletWithdrawal> findByClientOrderId(String clientOrderId);

    List<WalletWithdrawal> findByStatusInOrderByCreatedAtAsc(List<WithdrawalStatus> statuses);

    Page<WalletWithdrawal> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndStatusIn(String userId, Collection<WithdrawalStatus> statuses);

    @Query("""
        SELECT COALESCE(SUM(w.amountMicro + w.feeMicro), 0)
        FROM WalletWithdrawal w
        WHERE w.userId = :userId AND w.status IN :statuses
        """)
    long sumPendingAmountMicro(@Param("userId") String userId,
                               @Param("statuses") Collection<WithdrawalStatus> statuses);

    long countByStatus(WithdrawalStatus status);

    @Query("""
        SELECT COALESCE(SUM(w.amountMicro + w.feeMicro), 0)
        FROM WalletWithdrawal w
        WHERE w.status = com.chat99.server.wallet.WithdrawalStatus.COMPLETED
          AND w.completedAt >= :start AND w.completedAt < :end
        """)
    long sumCompletedAmountMicroBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
        SELECT COUNT(w)
        FROM WalletWithdrawal w
        WHERE w.status = com.chat99.server.wallet.WithdrawalStatus.FAILED
          AND w.createdAt >= :start AND w.createdAt < :end
        """)
    long countFailedBetween(@Param("start") Instant start, @Param("end") Instant end);
}
