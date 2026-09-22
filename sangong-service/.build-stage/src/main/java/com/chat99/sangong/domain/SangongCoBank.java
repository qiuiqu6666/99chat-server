package com.chat99.sangong.domain;
import java.time.Instant;
public class SangongCoBank {
    private long id; private long roundId; private long userId; private long amount; private Instant createdAt;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public long getRoundId(){return roundId;} public void setRoundId(long v){roundId=v;}
    public long getUserId(){return userId;} public void setUserId(long v){userId=v;}
    public long getAmount(){return amount;} public void setAmount(long v){amount=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
