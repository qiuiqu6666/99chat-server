package com.chat99.sangong.domain;

import java.time.Instant;

/**
 * 每租户代理实例。
 * 一个 sangong_user_groups[group_id] 可以被每个租户独立"实例化"成一个代理分组：
 * - 同一个 IM 用户 (agent_im_user_id) 可以在多个租户代理(但每个 (tenant_id, group_id) 一份实例)
 * - 不同租户可以有不同的 max_rebate_pct
 */
public class SangongTenantAgentGroup {
    private long id;
    private String tenantId;
    private long groupId;
    private String agentImUserId;
    private double maxRebatePct;
    private boolean active = true;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() { return id; } public void setId(long v) { id = v; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { tenantId = v; }
    public long getGroupId() { return groupId; } public void setGroupId(long v) { groupId = v; }
    public String getAgentImUserId() { return agentImUserId; } public void setAgentImUserId(String v) { agentImUserId = v; }
    public double getMaxRebatePct() { return maxRebatePct; } public void setMaxRebatePct(double v) { maxRebatePct = v; }
    public boolean isActive() { return active; } public void setActive(boolean v) { active = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
}