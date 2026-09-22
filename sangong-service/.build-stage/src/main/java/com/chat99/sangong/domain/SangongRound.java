package com.chat99.sangong.domain;
import java.time.Instant;
public class SangongRound {
    public static final String AWAIT_BANKER="await_banker", AWAIT_BANKER_DOOR="await_banker_door",
        BETTING="betting", CO_BANK_CLOSED="co_bank_closed", SETTLED="settled", VOIDED="voided";
    private long id; private long sessionId; private int periodNo; private String status;
    private Long bankerUserId; private Integer bankerDoor; private Long bankerLimit;
    private Instant coBankClosedAt; private Instant betWindowOpenAt; private Instant betWindowCloseAt;
    private Long betWindowCloseMessageId; private Long betSummaryTextMsgSeq; private Long betSummaryImageMsgSeq;
    private Long coBankSummaryMsgSeq;
    private Instant drawLockedAt; private Instant settledAt;
    public boolean allowsBetting() {
        return (BETTING.equals(status) || CO_BANK_CLOSED.equals(status)) && drawLockedAt == null;
    }
    public boolean isBetWindowOpen() {
        return betWindowOpenAt != null && betWindowCloseAt == null;
    }
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public long getSessionId(){return sessionId;} public void setSessionId(long v){sessionId=v;}
    public int getPeriodNo(){return periodNo;} public void setPeriodNo(int v){periodNo=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Long getBankerUserId(){return bankerUserId;} public void setBankerUserId(Long v){bankerUserId=v;}
    public Integer getBankerDoor(){return bankerDoor;} public void setBankerDoor(Integer v){bankerDoor=v;}
    public Long getBankerLimit(){return bankerLimit;} public void setBankerLimit(Long v){bankerLimit=v;}
    public Instant getCoBankClosedAt(){return coBankClosedAt;} public void setCoBankClosedAt(Instant v){coBankClosedAt=v;}
    public Instant getBetWindowOpenAt(){return betWindowOpenAt;} public void setBetWindowOpenAt(Instant v){betWindowOpenAt=v;}
    public Instant getBetWindowCloseAt(){return betWindowCloseAt;} public void setBetWindowCloseAt(Instant v){betWindowCloseAt=v;}
    public Long getBetWindowCloseMessageId(){return betWindowCloseMessageId;} public void setBetWindowCloseMessageId(Long v){betWindowCloseMessageId=v;}
    public Long getBetSummaryTextMsgSeq(){return betSummaryTextMsgSeq;} public void setBetSummaryTextMsgSeq(Long v){betSummaryTextMsgSeq=v;}
    public Long getBetSummaryImageMsgSeq(){return betSummaryImageMsgSeq;} public void setBetSummaryImageMsgSeq(Long v){betSummaryImageMsgSeq=v;}
    public Long getCoBankSummaryMsgSeq(){return coBankSummaryMsgSeq;} public void setCoBankSummaryMsgSeq(Long v){coBankSummaryMsgSeq=v;}
    public Instant getDrawLockedAt(){return drawLockedAt;} public void setDrawLockedAt(Instant v){drawLockedAt=v;}
    public Instant getSettledAt(){return settledAt;} public void setSettledAt(Instant v){settledAt=v;}
}
