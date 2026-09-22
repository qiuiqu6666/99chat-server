package com.chat99.sangong.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BetTextParserTest {
    private final BetTextParser parser = new BetTextParser();

    @Test
    void parsesSingleDoorFormats() {
        for (String text : List.of("1.200", "1；200", "1，200", "1/200", "1（200）", "1 200", "1。200")) {
            BetTextParser.Parsed parsed = parser.parse(text);
            assertNotNull(parsed, text);
            assertEquals(List.of(1), parsed.doors(), text);
            assertEquals(200, parsed.amount(), text);
            assertFalse(parsed.isAllIdle(), text);
        }
    }

    @Test
    void parsesMultiDoorRun() {
        BetTextParser.Parsed parsed = parser.parse("234/200");
        assertNotNull(parsed);
        assertEquals(List.of(2, 3, 4), parsed.doors());
        assertEquals(200, parsed.amount());
    }

    @Test
    void parsesDoorTen() {
        BetTextParser.Parsed parsed = parser.parse("10；500");
        assertNotNull(parsed);
        assertEquals(List.of(10), parsed.doors());
        assertEquals(500, parsed.amount());
    }

    @Test
    void parsesAllIdle() {
        BetTextParser.Parsed quan = parser.parse("全200");
        assertNotNull(quan);
        assertTrue(quan.isAllIdle());
        assertEquals(200, quan.amount());
        assertEquals("全", parser.allIdleKeyword(quan));

        BetTextParser.Parsed gong = parser.parse("公2000");
        assertNotNull(gong);
        assertTrue(gong.isAllIdle());
        assertEquals("公", parser.allIdleKeyword(gong));
    }

    @Test
    void rejectsInvalidTexts() {
        assertNull(parser.parse(""));
        assertNull(parser.parse("你好"));
        assertNull(parser.parse("1.0.200"));
        // "0" 在多门连写中表示 10 门:parse 阶段不再拒绝
        BetTextParser.Parsed zeroDoor = parser.parse("0/200");
        assertNotNull(zeroDoor);
        assertEquals(List.of(10), zeroDoor.doors());
        // 6 门游戏中 10 门被 resolve 阶段剔除 → 空 → null
        assertNull(parser.parseAndResolve("0/200", 6, null));
        // 10 门游戏中 0/200 仍可解析为单门 10
        BetTextParser.Resolved tenDoors = parser.resolve(zeroDoor, 10, 4);
        assertNotNull(tenDoors);
        assertEquals(List.of(10), tenDoors.doors());
        assertNull(parser.parse("1/0"));
    }

    @Test
    void zeroInMultiDoorMeansDoorTen() {
        // 6 门游戏 庄门=2:024 → 0 视作 10 门(6 门无 10 门被剔),2 是庄门被剔,保留 [4]
        BetTextParser.Resolved sixDoors = parser.resolve(parser.parse("024.100"), 6, 2);
        assertNotNull(sixDoors);
        assertEquals(List.of(4), sixDoors.doors());

        // 10 门游戏 庄门=2:024 → 0 视作 10 门保留,2 是庄门被剔,保留 [10, 4](保持 parse 阶段顺序)
        BetTextParser.Resolved tenDoors = parser.resolve(parser.parse("024.100"), 10, 2);
        assertNotNull(tenDoors);
        assertEquals(List.of(10, 4), tenDoors.doors());

        // 0234 在 6 门游戏 庄门=5 → [2, 3, 4](0 视作 10 被剔,5 不在 doors 中)
        BetTextParser.Resolved seq = parser.resolve(parser.parse("0234/300"), 6, 5);
        assertNotNull(seq);
        assertEquals(List.of(2, 3, 4), seq.doors());

        // 300 在 6 门游戏 庄门=1 → [3](两个 0 都视作 10 被剔,3 保留,1 不在 doors 中)
        BetTextParser.Resolved threeHundred = parser.resolve(parser.parse("300/50"), 6, 1);
        assertNotNull(threeHundred);
        assertEquals(List.of(3), threeHundred.doors());

        // 0100 在 6 门游戏 庄门=5 → [10,1,10,10] resolve 后 6 门剔 10,剩 [1],再剔庄门 5(不在),剩 [1]
        // 注意:这里 1 不被庄门 5 剔除,保留 [1]
        BetTextParser.Resolved ten100 = parser.resolve(parser.parse("0100/100"), 6, 5);
        assertNotNull(ten100);
        assertEquals(List.of(1), ten100.doors());

        // 0100 在 6 门游戏 庄门=1 → [10,1,10,10] resolve 后 6 门剔 10,剩 [1],庄门 1 被剔 → null
        assertNull(parser.parseAndResolve("0100/100", 6, 1));
    }

    @Test
    void resolveAllIdleExpandsToNonBankerDoors() {
        BetTextParser.Parsed parsed = parser.parse("全100");
        BetTextParser.Resolved resolved = parser.resolve(parsed, 6, 4);
        assertNotNull(resolved);
        assertEquals(List.of(1, 2, 3, 5, 6), resolved.doors());
        assertEquals(100, resolved.amount());

        // 未定庄门时全门下注不可解析
        assertNull(parser.resolve(parsed, 6, null));
        assertEquals("尚未定庄门，无法使用全门下注",
            parser.resolveRejectReason(parsed, 6, null));
    }

    @Test
    void resolveDropsBankerAndOutOfRangeDoors() {
        BetTextParser.Parsed parsed = parser.parse("234/200");
        BetTextParser.Resolved resolved = parser.resolve(parsed, 6, 3);
        assertNotNull(resolved);
        assertEquals(List.of(2, 4), resolved.doors());

        // 全部门号越界
        BetTextParser.Parsed outOfRange = parser.parse("789/200");
        assertNull(parser.resolve(outOfRange, 6, null));
    }

    @Test
    void bankerOnlyBetRejectedWithReason() {
        BetTextParser.Parsed parsed = parser.parse("3.500");
        assertNull(parser.resolve(parsed, 6, 3));
        assertTrue(parser.touchesOnlyBankerDoor(parsed, 6, 3));
        assertEquals("庄门不可下注", parser.resolveRejectReason(parsed, 6, 3));
    }

    @Test
    void parseAndResolveReturnsResolvedDoors() {
        BetTextParser.Parsed parsed = parser.parseAndResolve("全300", 6, 2);
        assertNotNull(parsed);
        assertTrue(parsed.isAllIdle());
        assertEquals(List.of(1, 3, 4, 5, 6), parsed.doors());
        assertEquals(300, parsed.amount());
    }

    @Test
    void fullWidthDigitsNormalized() {
        BetTextParser.Parsed parsed = parser.parse("１．２００");
        assertNotNull(parsed);
        assertEquals(List.of(1), parsed.doors());
        assertEquals(200, parsed.amount());
    }

    @Test
    void lowDoorCountSkipsZero() {
        // 6 门游戏 024/200 → parse 阶段 0 跳过 → [2, 4]
        BetTextParser.Resolved sixDoors024 = parser.resolve(parser.parse("024/200"), 6, null);
        assertNotNull(sixDoors024);
        assertEquals(List.of(2, 4), sixDoors024.doors());
        assertEquals(200, sixDoors024.amount());

        // 6 门游戏 240/200 → parse 阶段 0 跳过 → [2, 4]
        BetTextParser.Resolved sixDoors240 = parser.resolve(parser.parse("240/200"), 6, null);
        assertNotNull(sixDoors240);
        assertEquals(List.of(2, 4), sixDoors240.doors());
        assertEquals(200, sixDoors240.amount());

        // 10 门游戏 024/200 → 0 = 10 门,保持向后兼容 → [10, 2, 4]
        BetTextParser.Parsed tenDoors024 = parser.parseAndResolve("024/200", 10, null);
        assertNotNull(tenDoors024);
        assertEquals(List.of(10, 2, 4), tenDoors024.doors());

        // 10 门游戏 240/200 → 按字符解析 '2','4','0'→10 → [2, 4, 10](保持原行为)
        BetTextParser.Parsed tenDoors240 = parser.parseAndResolve("240/200", 10, null);
        assertNotNull(tenDoors240);
        assertEquals(List.of(2, 4, 10), tenDoors240.doors());

        // 6 门游戏 6 门都下(纯数字) → 仍正常
        BetTextParser.Resolved sixDoors123 = parser.resolve(parser.parse("123/100"), 6, null);
        assertNotNull(sixDoors123);
        assertEquals(List.of(1, 2, 3), sixDoors123.doors());
    }
}
