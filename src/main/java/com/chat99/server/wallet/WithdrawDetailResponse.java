package com.chat99.server.wallet;

import java.time.Instant;

/** 提现查单 / 创建响应（Live Activity 进度契约）。 */
public record WithdrawDetailResponse(
    String id,
    String clientOrderId,
    WithdrawalStatus status,
    WithdrawStage stage,
    WalletCurrency currency,
    long amount,
    long fee,
    long amountMicro,
    long feeMicro,
    String network,
    String toAddress,
    String fromAddress,
    String txId,
    int confirmations,
    int requiredConfirmations,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String NETWORK_TRC20 = "TRC20";

    public static WithdrawDetailResponse from(WalletWithdrawal w, int requiredConfirmations, String fromAddress) {
        String id = w.getId() == null ? null : String.valueOf(w.getId());
        Instant updated = w.getUpdatedAt() != null ? w.getUpdatedAt()
            : (w.getCompletedAt() != null ? w.getCompletedAt() : w.getCreatedAt());
        return new WithdrawDetailResponse(
            id,
            w.getClientOrderId(),
            w.getStatus(),
            WithdrawStage.fromStatus(w.getStatus()),
            WalletCurrency.USDT,
            w.getAmountMicro(),
            w.getFeeMicro(),
            w.getAmountMicro(),
            w.getFeeMicro(),
            NETWORK_TRC20,
            w.getToAddress(),
            fromAddress,
            w.getTxId(),
            w.getConfirmations(),
            requiredConfirmations,
            w.getCreatedAt(),
            updated);
    }
}
