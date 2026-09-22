package com.chat99.sangong.domain;

import java.time.Instant;

/** 代理余额：每 (tenant_id, group_id) 一行。 */
public class SangongAgentBalance {
    private String tenantId;
    private long groupId;
    private String agentImUserId;
    private long balance;
    private Instant createdAt;
    private Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { tenantId = v; }
    public long getGroupId() { return groupId; }
    public void setGroupId(long v) { groupId = v; }
    public String getAgentImUserId() { return agentImUserId; }
    public void setAgentImUserId(String v) { agentImUserId = v; }
    public long getBalance() { return balance; }
    public void setBalance(long v) { balance = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}