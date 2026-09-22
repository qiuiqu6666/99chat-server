package com.chat99.server.wallet;

/** Live Activity / 系统通知对外五档阶段（由服务端 status 映射）。 */
public enum WithdrawStage {
    SUBMITTED,
    BROADCASTING,
    CONFIRMING,
    COMPLETED,
    FAILED;

    public static WithdrawStage fromStatus(WithdrawalStatus status) {
        if (status == null) {
            return SUBMITTED;
        }
        return switch (status) {
            case PENDING -> SUBMITTED;
            case BROADCASTING -> BROADCASTING;
            case CONFIRMING -> CONFIRMING;
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
        };
    }
}
