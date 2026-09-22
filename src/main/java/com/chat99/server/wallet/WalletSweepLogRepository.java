package com.chat99.server.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WalletSweepLogRepository extends JpaRepository<WalletSweepLog, Long>,
    JpaSpecificationExecutor<WalletSweepLog> {
}
