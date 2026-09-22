package com.chat99.server.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WalletExchangeOrderRepository extends JpaRepository<WalletExchangeOrder, Long>,
    JpaSpecificationExecutor<WalletExchangeOrder> {

    Page<WalletExchangeOrder> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
