package com.chat99.server.wallet;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletFeeConfigRepository extends JpaRepository<WalletFeeConfig, Long> {

    Optional<WalletFeeConfig> findBySceneAndCurrency(WalletFeeScene scene, WalletCurrency currency);
}
