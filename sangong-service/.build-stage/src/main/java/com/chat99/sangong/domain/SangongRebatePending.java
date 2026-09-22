package com.chat99.sangong.domain;

import java.time.Instant;

/** 返水幂等记录：(round_id, user_id, side) 唯一。 */
public class SangongRebatePending {
    public static final String SIDE_PLAYER_WIN = "player_win";
    public static final String SIDE_BANKER_LOSS = "banker_loss";

    private long id;
    private String tenantId;
    private long roundId;
    private long userId;
    private String side;
    private long playerAmount;
    private long playerRebate;
    private long agentRebate;
    private Instant processedAt;
    private Instant createdAt;

    public long getId() { return id; } public void setId(long v) { id = v; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { tenantId = v; }
    public long getRoundId() { return roundId; } public void setRoundId(long v) { roundId = v; }
    public long getUserId() { return userId; } public void setUserId(long v) { userId = v; }
    public String getSide() { return side; } public void setSide(String v) { side = v; }
    public long getPlayerAmount() { return playerAmount; } public void setPlayerAmount(long v) { playerAmount = v; }
    public long getPlayerRebate() { return playerRebate; } public void setPlayerRebate(long v) { playerRebate = v; }
    public long getAgentRebate() { return agentRebate; } public void setAgentRebate(long v) { agentRebate = v; }
    public Instant getProcessedAt() { return processedAt; } public void setProcessedAt(Instant v) { processedAt = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
}