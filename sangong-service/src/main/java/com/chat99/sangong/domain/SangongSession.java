package com.chat99.sangong.domain;
import java.time.Instant;
import java.time.LocalDate;
public class SangongSession {
    public static final String IDLE="idle", RUNNING="running";
    private long id; private String tenantId; private String status; private int currentPeriodNo; private Long currentRoundId;
    private Instant startedAt; private Instant stoppedAt;
    private LocalDate businessDate;
    private String batchNo;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public int getCurrentPeriodNo(){return currentPeriodNo;} public void setCurrentPeriodNo(int v){currentPeriodNo=v;}
    public Long getCurrentRoundId(){return currentRoundId;} public void setCurrentRoundId(Long v){currentRoundId=v;}
    public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;}
    public Instant getStoppedAt(){return stoppedAt;} public void setStoppedAt(Instant v){stoppedAt=v;}
    public LocalDate getBusinessDate(){return businessDate;} public void setBusinessDate(LocalDate v){businessDate=v;}
    public String getBatchNo(){return batchNo;} public void setBatchNo(String v){batchNo=v;}
}
