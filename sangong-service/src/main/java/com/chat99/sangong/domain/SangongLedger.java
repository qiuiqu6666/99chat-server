package com.chat99.sangong.domain;
import java.time.Instant;
public class SangongLedger {
    private long id; private long userId; private Long groupId; private Long sessionId;
    private String type; private long amount; private long balanceAfter;
    private String refType; private Long refId; private String note; private String operator; private Instant createdAt;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public long getUserId(){return userId;} public void setUserId(long v){userId=v;}
    public Long getGroupId(){return groupId;} public void setGroupId(Long v){groupId=v;}
    public Long getSessionId(){return sessionId;} public void setSessionId(Long v){sessionId=v;}
    public String getType(){return type;} public void setType(String v){type=v;}
    public long getAmount(){return amount;} public void setAmount(long v){amount=v;}
    public long getBalanceAfter(){return balanceAfter;} public void setBalanceAfter(long v){balanceAfter=v;}
    public String getRefType(){return refType;} public void setRefType(String v){refType=v;}
    public Long getRefId(){return refId;} public void setRefId(Long v){refId=v;}
    public String getNote(){return note;} public void setNote(String v){note=v;}
    public String getOperator(){return operator;} public void setOperator(String v){operator=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
