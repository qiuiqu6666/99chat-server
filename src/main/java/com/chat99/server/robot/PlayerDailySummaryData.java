package com.chat99.server.robot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerDailySummaryData extends PlayerSnapshotData {

    private BigDecimal pendingRebate;

    public BigDecimal getPendingRebate() {
        return pendingRebate;
    }

    public void setPendingRebate(BigDecimal pendingRebate) {
        this.pendingRebate = pendingRebate;
    }
}
