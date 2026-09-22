package com.chat99.server.adminapi;

import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletLedgerType;

/** 运营后台调账币种（与前端下拉一致）。 */
public enum AdminAdjustCurrency {
    USDT, TRX, CNY;

    public static AdminAdjustCurrency parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("currency required");
        }
        return switch (raw.trim().toUpperCase()) {
            case "USDT" -> USDT;
            case "TRX" -> TRX;
            case "CNY" -> CNY;
            default -> throw new IllegalArgumentException("unsupported currency: " + raw);
        };
    }

    /** 钱包余额字段（USDT / TRX sun / 平台币分）。 */
    public WalletCurrency ledgerCurrency() {
        return switch (this) {
            case USDT -> WalletCurrency.USDT;
            case TRX -> WalletCurrency.TRX;
            case CNY -> WalletCurrency.CNY;
        };
    }

    /** 写入流水表时的币种：平台币 CNY 用 99，与 App 账变一致。 */
    public WalletCurrency ledgerBookCurrency() {
        return switch (this) {
            case USDT -> WalletCurrency.USDT;
            case TRX -> WalletCurrency.TRX;
            case CNY -> WalletCurrency.PLATFORM;
        };
    }

    public WalletLedgerType ledgerTypeForAdjust(String direction) {
        return "add".equals(direction) ? WalletLedgerType.DEPOSIT : WalletLedgerType.WITHDRAW;
    }
}
