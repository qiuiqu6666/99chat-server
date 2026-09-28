package com.chat99.server.wallet;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 领取热路径结果。PROCESSING 只表示 Redis 已抢到份额并写入 Stream，不表示 MySQL 余额已增加。
 * amount 单位为分。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedPacketClaimResult(
    boolean claimed,
    String claimId,
    Long amount,
    String status
) {
    public static final String PROCESSING = "PROCESSING";
    public static final String CREDITED = "CREDITED";
    public static final String EMPTY = "EMPTY";
    public static final String EXPIRED = "EXPIRED";
    public static final String NOT_MEMBER = "NOT_MEMBER";
    public static final String NOT_READY = "NOT_READY";
    public static final String ALREADY = "ALREADY";
}
