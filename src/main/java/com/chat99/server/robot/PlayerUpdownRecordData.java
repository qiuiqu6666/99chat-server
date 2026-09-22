package com.chat99.server.robot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerUpdownRecordData {

    private String recordId;
    private String wxid;
    private String playerNo;
    private String nickname;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceDelta;
    private BigDecimal balanceAfter;
    private BigDecimal totalUpAfter;
    private BigDecimal totalDownAfter;
    private Long approvedAt;
    private String status;
    private String approvalSource;

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

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

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceDelta() {
        return balanceDelta;
    }

    public void setBalanceDelta(BigDecimal balanceDelta) {
        this.balanceDelta = balanceDelta;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public BigDecimal getTotalUpAfter() {
        return totalUpAfter;
    }

    public void setTotalUpAfter(BigDecimal totalUpAfter) {
        this.totalUpAfter = totalUpAfter;
    }

    public BigDecimal getTotalDownAfter() {
        return totalDownAfter;
    }

    public void setTotalDownAfter(BigDecimal totalDownAfter) {
        this.totalDownAfter = totalDownAfter;
    }

    public Long getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Long approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApprovalSource() {
        return approvalSource;
    }

    public void setApprovalSource(String approvalSource) {
        this.approvalSource = approvalSource;
    }
}
