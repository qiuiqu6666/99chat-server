package com.chat99.sangong.domain;
import java.time.Instant;
public class SangongImMessage {
    public static final String OUTCOME_STORED = "stored";
    public static final String OUTCOME_IGNORED = "ignored";
    public static final String OUTCOME_PENDING_SUFFICIENT = "pending_sufficient";
    public static final String OUTCOME_PENDING_INSUFFICIENT = "pending_insufficient";
    public static final String OUTCOME_PENDING_REJECTED = "pending_rejected";
    public static final String OUTCOME_BET_PLACED = "bet_placed";
    public static final String OUTCOME_BET_FAILED = "bet_failed";

    private long id; private Long roundId; private String groupId; private String imUserId; private String nickname;
    private Long msgSeq; private String msgId; private Long msgTime; private String text;
    private String callbackCommand; private String rawPayload; private String outcome; private String outcomeDetail;
    private Long betId; private Instant createdAt;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public Long getRoundId(){return roundId;} public void setRoundId(Long v){roundId=v;}
    public String getGroupId(){return groupId;} public void setGroupId(String v){groupId=v;}
    public String getImUserId(){return imUserId;} public void setImUserId(String v){imUserId=v;}
    public String getNickname(){return nickname;} public void setNickname(String v){nickname=v;}
    public Long getMsgSeq(){return msgSeq;} public void setMsgSeq(Long v){msgSeq=v;}
    public String getMsgId(){return msgId;} public void setMsgId(String v){msgId=v;}
    public Long getMsgTime(){return msgTime;} public void setMsgTime(Long v){msgTime=v;}
    public String getText(){return text;} public void setText(String v){text=v;}
    public String getCallbackCommand(){return callbackCommand;} public void setCallbackCommand(String v){callbackCommand=v;}
    public String getRawPayload(){return rawPayload;} public void setRawPayload(String v){rawPayload=v;}
    public String getOutcome(){return outcome;} public void setOutcome(String v){outcome=v;}
    public String getOutcomeDetail(){return outcomeDetail;} public void setOutcomeDetail(String v){outcomeDetail=v;}
    public Long getBetId(){return betId;} public void setBetId(Long v){betId=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
