package com.chat99.server.wallet;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 红包领取明细（富集币种/手气最佳/昵称头像）。昵称头像为空时前端可自行用 IM 补全。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedPacketClaimRecord(
    String id,
    String userId,
    String currency,
    long amount,
    Instant createdAt,
    boolean bestLuck,
    String nickName,
    String avatarUrl) {
}
