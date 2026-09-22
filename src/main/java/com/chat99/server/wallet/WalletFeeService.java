package com.chat99.server.wallet;

import org.springframework.stereotype.Service;

@Service
public class WalletFeeService {

    private final WalletFeeConfigRepository feeRepository;

    public WalletFeeService(WalletFeeConfigRepository feeRepository) {
        this.feeRepository = feeRepository;
    }

    /** USDT internal transfer always zero. */
    public long calculateFee(WalletFeeScene scene, WalletCurrency currency, long amount) {
        if (currency == WalletCurrency.USDT
            && scene != WalletFeeScene.WITHDRAW
            && scene != WalletFeeScene.LIVE_TIP) {
            return 0;
        }
        WalletFeeConfig cfg = feeRepository.findBySceneAndCurrency(scene, currency).orElse(null);
        if (cfg == null || !cfg.isEnabled() || cfg.getFeeType() == WalletFeeType.NONE) {
            return 0;
        }
        long fee = switch (cfg.getFeeType()) {
            case PERCENT -> {
                long raw = Math.multiplyExact(amount, cfg.getFeeValue()) / 10_000L;
                yield applyBounds(raw, cfg.getMinFee(), cfg.getMaxFee());
            }
            case FIXED -> cfg.getFeeValue();
            default -> 0;
        };
        if (fee >= amount && amount > 0) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "FEE_EXCEEDS_AMOUNT");
        }
        return fee;
    }

    private static long applyBounds(long fee, Long min, Long max) {
        if (min != null && fee < min) fee = min;
        if (max != null && fee > max) fee = max;
        return fee;
    }
}
