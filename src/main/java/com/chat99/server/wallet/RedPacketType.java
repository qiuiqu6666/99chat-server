package com.chat99.server.wallet;

public enum RedPacketType {
    NORMAL_GROUP,
    LUCKY_GROUP,
    EXCLUSIVE,
    NORMAL_C2C,
    /** 群转账：群内点对点直接到账；账变展示「群转账」 */
    GROUP_TRANSFER;

    /**
     * 对外统一类型口径（/wallet/ledger 与 /wallet/red-packet/{id} 的 packetType 字段）：
     * 普通（单聊/群聊）→ COMMON；拼手气 → LUCKY；专属红包、群转账原样。
     * 发送入参（/red-packet/send）与 packet.packetType 仍为原始枚举名。
     */
    public String apiCode() {
        return switch (this) {
            case NORMAL_GROUP, NORMAL_C2C -> "COMMON";
            case LUCKY_GROUP -> "LUCKY";
            case EXCLUSIVE -> "EXCLUSIVE";
            case GROUP_TRANSFER -> "GROUP_TRANSFER";
        };
    }
}
