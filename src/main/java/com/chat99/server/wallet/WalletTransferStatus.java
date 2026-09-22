package com.chat99.server.wallet;

/** 平台内转账状态。成功入账为 COMPLETED；复制/异步落库窗口可为 PENDING/PROCESSING。 */
public enum WalletTransferStatus {
    PENDING,
    PROCESSING,
    COMPLETED
}
