package com.chat99.sangong.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HandTypeServiceTest {
    private final HandTypeService service = new HandTypeService();

    @Test
    void oneYuanIsBiggest() {
        HandTypeService.HandInfo info = service.analyze(100);
        assertEquals("one_yuan", info.handType());
        assertEquals("1.00", info.handLabel());
        assertEquals("1.00", info.amountDisplay());
        assertEquals(100, info.compareValue());
        assertNull(info.pointValue());
        assertNull(info.pairValue());
    }

    @Test
    void pairWhenDigitsEqual() {
        HandTypeService.HandInfo info = service.analyze(55);
        assertEquals("pair", info.handType());
        assertEquals("对子55", info.handLabel());
        assertEquals(55, info.pairValue());
        assertEquals("0.55", info.amountDisplay());
    }

    @Test
    void niuniuWhenDigitsSumToTen() {
        HandTypeService.HandInfo info = service.analyze(46);
        assertEquals("niuniu", info.handType());
        assertEquals("牛牛", info.handLabel());
        assertNull(info.pointValue());
    }

    @Test
    void pointHands() {
        HandTypeService.HandInfo info = service.analyze(23);
        assertEquals("point", info.handType());
        assertEquals(5, info.pointValue());
        assertEquals("5点", info.handLabel());

        // 8+9=17 → 7点
        HandTypeService.HandInfo p7 = service.analyze(89);
        assertEquals(7, p7.pointValue());

        // 0+9=9 → 9点（0 参与加法为 0）
        HandTypeService.HandInfo p9 = service.analyze(9);
        assertEquals("point", p9.handType());
        assertEquals(9, p9.pointValue());
    }

    @Test
    void compareValueZeroBeatsNine() {
        // 数字优先级 0 > 9 > 8 > … > 1
        HandTypeService.HandInfo withZero = service.analyze(9); // 0 与 9
        HandTypeService.HandInfo withoutZero = service.analyze(89); // 8 与 9
        assertTrue(withZero.compareValue() > withoutZero.compareValue());

        // 对子 99 与对子 11：99 大
        assertTrue(service.analyze(99).compareValue() > service.analyze(11).compareValue());
        // 对子 00（hundredths 无法表达 00，最低 1）—— 10 为 1 和 0
        HandTypeService.HandInfo ten = service.analyze(10);
        assertEquals("point", ten.handType()); // 1+0=1 点
        assertEquals(1, ten.pointValue());
    }

    @Test
    void invalidHundredthsRejected() {
        assertThrows(RuntimeException.class, () -> service.analyze(0));
        assertThrows(RuntimeException.class, () -> service.analyze(101));
        assertThrows(RuntimeException.class, () -> service.analyze(-5));
    }
}
