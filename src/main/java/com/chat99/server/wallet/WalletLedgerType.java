package com.chat99.server.wallet;

public enum WalletLedgerType {
    DEPOSIT,
    WITHDRAW,
    TRANSFER_OUT,
    TRANSFER_IN,
    RED_PACKET_SEND,
    RED_PACKET_RECEIVE,
    RED_PACKET_REFUND,
    EXCHANGE_OUT,
    EXCHANGE_IN,
    FEE,
    EXCHANGE_SURPLUS,
    ADMIN_ADJUST,
    /** 仅 API 筛选；库内提现失败退款为 {@link #WITHDRAW} + {@code refType=WITHDRAW_REFUND}。 */
    WITHDRAW_REFUND,
    /** 生活缴费（手机充值 / 水电燃气）平台侧扣款。 */
    LIFE_PAYMENT,
    /** 群直播打赏支出。 */
    LIVE_TIP_OUT,
    /** 群直播打赏收入。 */
    LIVE_TIP_IN
}
