package com.chat99.sangong.domain;

import java.time.Instant;

/** 游戏租户 = 一个独立下注 IM 群（数据互不互通）。 */
public class SangongTenant {
    private String tenantId;
    private String name;
    private String imGroupGameId;
    private String imGroupAdminStatsId;
    private String imGroupLedgerId;
    private String imGroupWaterId;
    private String imBotUserId;
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImGroupGameId() { return imGroupGameId; }
    public void setImGroupGameId(String imGroupGameId) { this.imGroupGameId = imGroupGameId; }
    public String getImGroupAdminStatsId() { return imGroupAdminStatsId; }
    public void setImGroupAdminStatsId(String v) { this.imGroupAdminStatsId = v; }
    public String getImGroupLedgerId() { return imGroupLedgerId; }
    public void setImGroupLedgerId(String v) { this.imGroupLedgerId = v; }
    public String getImGroupWaterId() { return imGroupWaterId; }
    public void setImGroupWaterId(String v) { this.imGroupWaterId = v; }
    public String getImBotUserId() { return imBotUserId; }
    public void setImBotUserId(String v) { this.imBotUserId = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
