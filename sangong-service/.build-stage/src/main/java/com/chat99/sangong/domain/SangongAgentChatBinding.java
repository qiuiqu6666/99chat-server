package com.chat99.sangong.domain;

import java.time.Instant;

/** 代理用户进入哪个 IM 群时显示哪个租户的代理入口。 */
public class SangongAgentChatBinding {
    private long id;
    private String tenantId;
    private String agentImUserId;
    private String agentImGroupId;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() { return id; }
    public void setId(long value) { id = value; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getAgentImUserId() { return agentImUserId; }
    public void setAgentImUserId(String value) { agentImUserId = value; }
    public String getAgentImGroupId() { return agentImGroupId; }
    public void setAgentImGroupId(String value) { agentImGroupId = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
