package com.chat99.sangong.domain;
public class SangongUser {
    private long id; private String tenantId; private String imUserId; private String nickname; private long balance; private Long groupId;
    private double playerRebatePct;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getImUserId(){return imUserId;} public void setImUserId(String v){imUserId=v;}
    public String getNickname(){return nickname;} public void setNickname(String v){nickname=v;}
    public long getBalance(){return balance;} public void setBalance(long v){balance=v;}
    public Long getGroupId(){return groupId;} public void setGroupId(Long v){groupId=v;}
    public double getPlayerRebatePct(){return playerRebatePct;} public void setPlayerRebatePct(double v){playerRebatePct=v;}
}
