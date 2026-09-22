package com.chat99.server.wallet;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 钱包历史统一记录结构（富集多业务单字段，前端按 ledgerType+source+direction 区分）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletLedgerRecord(
    String id,
    String ledgerType,
    String source,
    String refType,
    String refId,
    String status,
    String currency,
    long amount,
    String direction,
    Instant createdAt,
    String network,
    String txId,
    String fromAddress,
    String toAddress,
    Long feeAmount,
    Integer confirmations,
    String failReason,
    String fromUserId,
    String toUserId,
    String counterpartUserId,
    String remark,
    String memo,
    String clientOrderId,
    String packetId,
    String packetType,
    Integer packetCount,
    Integer remainingCount,
    Long totalAmount,
    String greeting,
    String senderUserId,
    String groupId,
    Instant expiresAt,
    Long inputAmount,
    Long outputAmount,
    String rateSnapshot,
    /** 展示标题；群转账为「群转账」，其它红包通常由客户端按类型映射 */
    String displayTitle,
    /** 群转账收款人用户ID（GROUP_TRANSFER 时与 toUserId 一致；来源 exclusiveUserId） */
    String receiverUserId,
    /** 群转账收款人昵称（数据库 users 表；查不到为 null，前端可用 receiverUserId 兜底查询） */
    String receiverName,
    /** 群转账发起人昵称 */
    String senderName,
    /** 收款人昵称（与 receiverName 一致，历史字段兼容） */
    String toUserName,
    /** 发送人头像 URL（转账/红包；来自数据库 users 表） */
    String senderAvatar,
    /** 收款人头像 URL（转账/定向红包/群转账；来自数据库 users 表） */
    String receiverAvatar,
    /** 群名称（群红包/群转账；来自数据库 group_profile 表） */
    String groupName,
    /** 群头像 URL（群红包/群转账；来自数据库 group_profile 表，无自定义头像时为默认群头像） */
    String groupAvatar) {
}
