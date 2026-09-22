package com.chat99.sangong.domain;
import java.time.Instant;
public class SangongBet {
    private long id; private long roundId; private long userId; private int door; private long amount;
    private boolean proxy; private Long imMessageId; private Instant createdAt;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public long getRoundId(){return roundId;} public void setRoundId(long v){roundId=v;}
    public long getUserId(){return userId;} public void setUserId(long v){userId=v;}
    public int getDoor(){return door;} public void setDoor(int v){door=v;}
    public long getAmount(){return amount;} public void setAmount(long v){amount=v;}
    public boolean isProxy(){return proxy;} public void setProxy(boolean v){proxy=v;}
    public Long getImMessageId(){return imMessageId;} public void setImMessageId(Long v){imMessageId=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
