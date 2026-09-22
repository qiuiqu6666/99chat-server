package com.chat99.server.robot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerSnapshotData {

    private String wxid;
    private String playerNo;
    private String nickname;
    private String displayName;
    private String remark;
    private String playerType;
    private BigDecimal balance;
    private String directParentWxid;
    private String directParentNo;
    private String parentPath;
    private Integer levelNo;
    private Boolean active;
    private BigDecimal totalFlow;
    private BigDecimal usedFlow;
    private BigDecimal remainingFlow;
    private BigDecimal agentPendingFlow;
    private BigDecimal agentPendingRebate;
    private BigDecimal totalUp;
    private BigDecimal totalDown;
    private BigDecimal totalProfitLoss;
    private BigDecimal rebateRate;
    private String rebateRateUnit;
    private BigDecimal totalRebate;

    public String getWxid() {
        return wxid;
    }

    public void setWxid(String wxid) {
        this.wxid = wxid;
    }

    public String getPlayerNo() {
        return playerNo;
    }

    public void setPlayerNo(String playerNo) {
        this.playerNo = playerNo;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getPlayerType() {
        return playerType;
    }

    public void setPlayerType(String playerType) {
        this.playerType = playerType;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getDirectParentWxid() {
        return directParentWxid;
    }

    public void setDirectParentWxid(String directParentWxid) {
        this.directParentWxid = directParentWxid;
    }

    public String getDirectParentNo() {
        return directParentNo;
    }

    public void setDirectParentNo(String directParentNo) {
        this.directParentNo = directParentNo;
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    public Integer getLevelNo() {
        return levelNo;
    }

    public void setLevelNo(Integer levelNo) {
        this.levelNo = levelNo;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public BigDecimal getTotalFlow() {
        return totalFlow;
    }

    public void setTotalFlow(BigDecimal totalFlow) {
        this.totalFlow = totalFlow;
    }

    public BigDecimal getUsedFlow() {
        return usedFlow;
    }

    public void setUsedFlow(BigDecimal usedFlow) {
        this.usedFlow = usedFlow;
    }

    public BigDecimal getRemainingFlow() {
        return remainingFlow;
    }

    public void setRemainingFlow(BigDecimal remainingFlow) {
        this.remainingFlow = remainingFlow;
    }

    public BigDecimal getAgentPendingFlow() {
        return agentPendingFlow;
    }

    public void setAgentPendingFlow(BigDecimal agentPendingFlow) {
        this.agentPendingFlow = agentPendingFlow;
    }

    public BigDecimal getAgentPendingRebate() {
        return agentPendingRebate;
    }

    public void setAgentPendingRebate(BigDecimal agentPendingRebate) {
        this.agentPendingRebate = agentPendingRebate;
    }

    public BigDecimal getTotalUp() {
        return totalUp;
    }

    public void setTotalUp(BigDecimal totalUp) {
        this.totalUp = totalUp;
    }

    public BigDecimal getTotalDown() {
        return totalDown;
    }

    public void setTotalDown(BigDecimal totalDown) {
        this.totalDown = totalDown;
    }

    public BigDecimal getTotalProfitLoss() {
        return totalProfitLoss;
    }

    public void setTotalProfitLoss(BigDecimal totalProfitLoss) {
        this.totalProfitLoss = totalProfitLoss;
    }

    public BigDecimal getRebateRate() {
        return rebateRate;
    }

    public void setRebateRate(BigDecimal rebateRate) {
        this.rebateRate = rebateRate;
    }

    public String getRebateRateUnit() {
        return rebateRateUnit;
    }

    public void setRebateRateUnit(String rebateRateUnit) {
        this.rebateRateUnit = rebateRateUnit;
    }

    public BigDecimal getTotalRebate() {
        return totalRebate;
    }

    public void setTotalRebate(BigDecimal totalRebate) {
        this.totalRebate = totalRebate;
    }
}
