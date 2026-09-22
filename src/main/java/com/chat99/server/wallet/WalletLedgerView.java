package com.chat99.server.wallet;

/**
 * App 资金流水展示 DTO：将运营调账等类型映射为客户端已支持的 ledgerType / currency。
 */
public record WalletLedgerView(
    Long id,
    String userId,
    String currency,
    long amount,
    long balanceAfter,
    String ledgerType,
    String refType,
    Long refId,
    String counterpartUserId,
    String remark,
    java.time.Instant createdAt) {

    public static WalletLedgerView from(WalletLedger e) {
        String displayType = mapLedgerType(e);
        String displayCurrency = mapCurrency(e.getCurrency());
        return new WalletLedgerView(
            e.getId(),
            e.getUserId(),
            displayCurrency,
            e.getAmount(),
            e.getBalanceAfter(),
            displayType,
            e.getRefType(),
            e.getRefId(),
            e.getCounterpartUserId(),
            e.getRemark(),
            e.getCreatedAt());
    }

    private static String mapLedgerType(WalletLedger e) {
        if (e.getLedgerType() == WalletLedgerType.ADMIN_ADJUST) {
            return e.getAmount() >= 0 ? WalletLedgerType.DEPOSIT.name() : WalletLedgerType.WITHDRAW.name();
        }
        return e.getLedgerType().name();
    }

    /** CNY 平台币在 App 侧展示为 99。 */
    private static String mapCurrency(WalletCurrency currency) {
        if (currency == WalletCurrency.CNY || currency == WalletCurrency.PLATFORM) {
            return WalletCurrency.PLATFORM.getApiCode();
        }
        return currency.getApiCode();
    }
}
