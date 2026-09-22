package com.chat99.sangong.domain;
public class SangongRoundDraw {
    public static final String HAND_ONE_YUAN="one_yuan", HAND_PAIR="pair", HAND_POINT="point", HAND_NIUNIU="niuniu";
    private long id; private long roundId; private int door; private String amountRaw; private int amountHundredths;
    private String handType; private String handLabel; private Integer pointValue; private Integer pairValue; private int compareValue;
    public long getId(){return id;} public void setId(long id){this.id=id;}
    public long getRoundId(){return roundId;} public void setRoundId(long v){roundId=v;}
    public int getDoor(){return door;} public void setDoor(int v){door=v;}
    public String getAmountRaw(){return amountRaw;} public void setAmountRaw(String v){amountRaw=v;}
    public int getAmountHundredths(){return amountHundredths;} public void setAmountHundredths(int v){amountHundredths=v;}
    public String getHandType(){return handType;} public void setHandType(String v){handType=v;}
    public String getHandLabel(){return handLabel;} public void setHandLabel(String v){handLabel=v;}
    public Integer getPointValue(){return pointValue;} public void setPointValue(Integer v){pointValue=v;}
    public Integer getPairValue(){return pairValue;} public void setPairValue(Integer v){pairValue=v;}
    public int getCompareValue(){return compareValue;} public void setCompareValue(int v){compareValue=v;}
    public String amountDisplay(){ return amountHundredths==100 ? "1.00" : String.format("0.%02d", amountHundredths); }
}
