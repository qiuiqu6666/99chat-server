package com.chat99.server.wallet;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WalletWithdrawLiveActivityRepository extends JpaRepository<WalletWithdrawLiveActivity, Long> {

    Optional<WalletWithdrawLiveActivity> findByWithdrawalId(long withdrawalId);

    @Query("""
        SELECT a FROM WalletWithdrawLiveActivity a
        WHERE a.lastPushEvent IS NULL OR a.lastPushEvent <> 'end'
        """)
    List<WalletWithdrawLiveActivity> findNotEnded();
}
