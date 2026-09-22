package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletFeeServiceLiveTipTest {

    @Mock WalletFeeConfigRepository feeRepository;

    WalletFeeService service;

    @BeforeEach
    void setUp() {
        service = new WalletFeeService(feeRepository);
    }

    @Test
    void usdtNonWithdrawNonLiveTipIsZero() {
        assertThat(service.calculateFee(WalletFeeScene.RED_PACKET_SEND, WalletCurrency.USDT, 1_000_000L))
            .isEqualTo(0);
        assertThat(service.calculateFee(WalletFeeScene.TRANSFER_PLATFORM, WalletCurrency.USDT, 1_000_000L))
            .isEqualTo(0);
        assertThat(service.calculateFee(WalletFeeScene.EXCHANGE, WalletCurrency.USDT, 1_000_000L))
            .isEqualTo(0);
    }

    @Test
    void usdtLiveTipUsesConfig() {
        WalletFeeConfig cfg = new WalletFeeConfig();
        cfg.setScene(WalletFeeScene.LIVE_TIP);
        cfg.setCurrency(WalletCurrency.USDT);
        cfg.setFeeType(WalletFeeType.PERCENT);
        cfg.setFeeValue(100L);
        cfg.setEnabled(true);
        when(feeRepository.findBySceneAndCurrency(WalletFeeScene.LIVE_TIP, WalletCurrency.USDT))
            .thenReturn(Optional.of(cfg));

        assertThat(service.calculateFee(WalletFeeScene.LIVE_TIP, WalletCurrency.USDT, 1_000_000L))
            .isEqualTo(10_000L);
    }

    @Test
    void usdtWithdrawStillUsesConfig() {
        WalletFeeConfig cfg = new WalletFeeConfig();
        cfg.setScene(WalletFeeScene.WITHDRAW);
        cfg.setCurrency(WalletCurrency.USDT);
        cfg.setFeeType(WalletFeeType.FIXED);
        cfg.setFeeValue(1_000_000L);
        cfg.setEnabled(true);
        when(feeRepository.findBySceneAndCurrency(WalletFeeScene.WITHDRAW, WalletCurrency.USDT))
            .thenReturn(Optional.of(cfg));

        assertThat(service.calculateFee(WalletFeeScene.WITHDRAW, WalletCurrency.USDT, 10_000_000L))
            .isEqualTo(1_000_000L);
    }
}
