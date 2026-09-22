package com.chat99.sangong.domain;

import java.time.Instant;

/** 代理账本：独立于 sangong_ledger。
 *  type ∈ {agent_transfer_in, agent_transfer_out, rebate_diff, rebate_correction} */
public class SangongAgentLedger {
    private long id;
    private String tenantId;
    private Long sessionId;
    private long groupId;
    private String agentImUserId;
    private String type;
    private long amount;
    private long balanceAfter;
    private String refType;
    private Long refId;
    private String note;
    private Instant createdAt;

    public static final String TYPE_TRANSFER_IN = "agent_transfer_in";
    public static final String TYPE_TRANSFER_OUT = "agent_transfer_out";
    public static final String TYPE_REBATE_DIFF = "rebate_diff";
    public static final String TYPE_REBATE_CORRECTION = "rebate_correction";

    public static final String REF_TRANSFER = "transfer";
    public static final String REF_ROUND = "round";
    public static final String REF_REBATE = "rebate";

    public long getId() { return id; } public void setId(long v) { id = v; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { tenantId = v; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long v) { sessionId = v; }
    public long getGroupId() { return groupId; } public void setGroupId(long v) { groupId = v; }
    public String getAgentImUserId() { return agentImUserId; } public void setAgentImUserId(String v) { agentImUserId = v; }
    public String getType() { return type; } public void setType(String v) { type = v; }
    public long getAmount() { return amount; } public void setAmount(long v) { amount = v; }
    public long getBalanceAfter() { return balanceAfter; } public void setBalanceAfter(long v) { balanceAfter = v; }
    public String getRefType() { return refType; } public void setRefType(String v) { refType = v; }
    public Long getRefId() { return refId; } public void setRefId(Long v) { refId = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
}
