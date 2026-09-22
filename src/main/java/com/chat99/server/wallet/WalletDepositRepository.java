package com.chat99.server.wallet;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletDepositRepository extends JpaRepository<WalletDeposit, Long>,
    JpaSpecificationExecutor<WalletDeposit> {

    Optional<WalletDeposit> findByTxIdAndLogIndex(String txId, int logIndex);

    Page<WalletDeposit> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndStatus(String userId, DepositStatus status);

    java.util.List<WalletDeposit> findByStatusOrderByCreatedAtAsc(DepositStatus status, Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(d.amountMicro), 0)
        FROM WalletDeposit d
        WHERE d.status = com.chat99.server.wallet.DepositStatus.CREDITED
          AND d.creditedAt >= :start AND d.creditedAt < :end
        """)
    long sumCreditedAmountMicroBetween(@Param("start") Instant start, @Param("end") Instant end);
}
