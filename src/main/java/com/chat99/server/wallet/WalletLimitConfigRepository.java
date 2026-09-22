package com.chat99.server.wallet;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletLimitConfigRepository extends JpaRepository<WalletLimitConfig, Long> {

    Optional<WalletLimitConfig> findBySceneAndCurrency(WalletLimitScene scene, WalletCurrency currency);
}
