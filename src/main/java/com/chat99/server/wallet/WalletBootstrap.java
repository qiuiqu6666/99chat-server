package com.chat99.server.wallet;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WalletBootstrap {

    private final WalletFeeConfigRepository feeRepository;
    private final WalletLimitConfigRepository limitRepository;
    private final WalletExchangeConfigRepository exchangeConfigRepository;
    private final WalletPlatformStatsRepository statsRepository;

    public WalletBootstrap(WalletFeeConfigRepository feeRepository,
                           WalletLimitConfigRepository limitRepository,
                           WalletExchangeConfigRepository exchangeConfigRepository,
                           WalletPlatformStatsRepository statsRepository) {
        this.feeRepository = feeRepository;
        this.limitRepository = limitRepository;
        this.exchangeConfigRepository = exchangeConfigRepository;
        this.statsRepository = statsRepository;
    }

    @PostConstruct
    @Transactional
    public void seed() {
        if (statsRepository.findById(1L).isEmpty()) {
            WalletPlatformStats s = new WalletPlatformStats();
            s.setId(1L);
            statsRepository.save(s);
        }
        if (exchangeConfigRepository.findById(1L).isEmpty()) {
            WalletExchangeConfig c = new WalletExchangeConfig();
            c.setId(1L);
            c.setMarkupBps(0);
            c.setFloatBps(50);
            c.setMinWithdrawUsdtMicro(1_000_000L);
            c.setEnabled(true);
            exchangeConfigRepository.save(c);
        }
        seedFee(WalletFeeScene.WITHDRAW, WalletCurrency.USDT, WalletFeeType.FIXED, 1_000_000L);
        seedFee(WalletFeeScene.TRANSFER_PLATFORM, WalletCurrency.PLATFORM, WalletFeeType.NONE, 0);
        seedFee(WalletFeeScene.RED_PACKET_SEND, WalletCurrency.PLATFORM, WalletFeeType.NONE, 0);
        seedLimit(WalletLimitScene.TRANSFER, WalletCurrency.USDT, 100_000_000_000_000L, 500_000_000_000_000L);
        seedLimit(WalletLimitScene.TRANSFER, WalletCurrency.PLATFORM, 1_000_000_00L, 5_000_000_00L);
        seedLimit(WalletLimitScene.RED_PACKET, WalletCurrency.USDT, 100_000_000_000_000L, 500_000_000_000_000L);
        seedLimit(WalletLimitScene.RED_PACKET, WalletCurrency.PLATFORM, 1_000_000_00L, 5_000_000_00L);
        seedLimit(WalletLimitScene.WITHDRAW, WalletCurrency.USDT, 5_000_000_000L, 5_000_000_000L);
        seedFee(WalletFeeScene.LIVE_TIP, WalletCurrency.USDT, WalletFeeType.NONE, 0);
        seedFee(WalletFeeScene.LIVE_TIP, WalletCurrency.PLATFORM, WalletFeeType.NONE, 0);
        seedFee(WalletFeeScene.LIVE_TIP, WalletCurrency.TRX, WalletFeeType.NONE, 0);
        seedFee(WalletFeeScene.LIVE_TIP, WalletCurrency.CNY, WalletFeeType.NONE, 0);
        seedLimit(WalletLimitScene.LIVE_TIP, WalletCurrency.USDT, 100_000_000_000_000L, 500_000_000_000_000L);
        seedLimit(WalletLimitScene.LIVE_TIP, WalletCurrency.PLATFORM, 1_000_000_00L, 5_000_000_00L);
        seedLimit(WalletLimitScene.LIVE_TIP, WalletCurrency.TRX, 100_000_000_000_000L, 500_000_000_000_000L);
        seedLimit(WalletLimitScene.LIVE_TIP, WalletCurrency.CNY, 1_000_000_00L, 5_000_000_00L);
    }

    private void seedFee(WalletFeeScene scene, WalletCurrency currency, WalletFeeType type, long value) {
        if (feeRepository.findBySceneAndCurrency(scene, currency).isPresent()) return;
        WalletFeeConfig c = new WalletFeeConfig();
        c.setScene(scene);
        c.setCurrency(currency);
        c.setFeeType(type);
        c.setFeeValue(value);
        c.setEnabled(true);
        feeRepository.save(c);
    }

    private void seedLimit(WalletLimitScene scene, WalletCurrency currency, long perTx, long daily) {
        if (limitRepository.findBySceneAndCurrency(scene, currency).isPresent()) return;
        WalletLimitConfig c = new WalletLimitConfig();
        c.setScene(scene);
        c.setCurrency(currency);
        c.setPerTxMax(perTx);
        c.setDailyMax(daily);
        c.setEnabled(true);
        limitRepository.save(c);
    }
}
