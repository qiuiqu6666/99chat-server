package com.chat99.sangong.service;

import org.springframework.stereotype.Service;

/**
 * 牌型分析：与 PHP HandTypeService 完全一致。
 * hundredths=100 → 1.00（最大）；x==y → 对子；x+y==10 → 牛牛；否则 (x+y)%10 点。
 */
@Service
public class HandTypeService {

    public record HandInfo(String handType, String handLabel, Integer pointValue,
                           Integer pairValue, int compareValue, String amountDisplay) {}

    public HandInfo analyze(int hundredths) {
        if (hundredths == 100) {
            return new HandInfo("one_yuan", "1.00", null, null, 100, "1.00");
        }
        if (hundredths < 1 || hundredths > 99) {
            throw new RuntimeException("开彩金额无效");
        }
        int x = hundredths / 10;
        int y = hundredths % 10;
        int compareValue = buildCompareValue(x, y);

        if (x == y) {
            int forward = 10 * x + y;
            return new HandInfo("pair", "对子" + forward, null, forward, compareValue,
                String.format("0.%02d", hundredths));
        }
        if (x + y == 10) {
            return new HandInfo("niuniu", "牛牛", null, null, compareValue,
                String.format("0.%02d", hundredths));
        }
        int pointValue = (x + y) % 10;
        return new HandInfo("point", pointValue + "点", pointValue, null, compareValue,
            String.format("0.%02d", hundredths));
    }

    /** 同点比大小：数字优先级 0 > 9 > 8 > … > 1；值 = 高位等级*100 + 低位等级（0 视为 10）。 */
    private int buildCompareValue(int x, int y) {
        int rx = digitCompareRank(x);
        int ry = digitCompareRank(y);
        return Math.max(rx, ry) * 100 + Math.min(rx, ry);
    }

    private int digitCompareRank(int digit) {
        return digit == 0 ? 10 : digit;
    }
}
