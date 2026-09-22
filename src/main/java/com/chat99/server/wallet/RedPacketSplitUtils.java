package com.chat99.server.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class RedPacketSplitUtils {

    public static final long MIN_UNIT = 1L;
    public static final int MAX_LUCKY_PACKET_COUNT = 100;

    private RedPacketSplitUtils() {}

    public static void validateLuckySend(long total, int count) {
        if (count <= 0 || total <= 0) {
            throw new IllegalArgumentException("invalid split");
        }
        if (count > MAX_LUCKY_PACKET_COUNT) {
            throw new IllegalArgumentException("packet count exceeds max");
        }
        if (total < count * MIN_UNIT) {
            throw new IllegalArgumentException("total less than minimum per packet");
        }
    }

    /** 微信二倍均值法：min(安全上限, 2×剩余人均)。 */
    public static long luckyMaxDraw(long remain, int remainCount, long minUnit) {
        if (remainCount <= 0 || remain < remainCount * minUnit) {
            throw new IllegalArgumentException("invalid split state");
        }
        if (remainCount == 1) {
            return remain;
        }
        long safeMax = remain - (long) (remainCount - 1) * minUnit;
        long cap = (2L * remain) / remainCount;
        return Math.min(safeMax, cap);
    }

    /** 生产路径：ThreadLocalRandom 真随机，不可用 packetId 复现。 */
    public static long drawLuckyAmount(long remain, int remainCount, long minUnit) {
        return drawLuckyAmount(remain, remainCount, minUnit, ThreadLocalRandom.current());
    }

    public static long drawLuckyAmount(long remain, int remainCount, long minUnit, Random rng) {
        if (remainCount == 1) {
            return remain;
        }
        long max = luckyMaxDraw(remain, remainCount, minUnit);
        if (max < minUnit) {
            throw new IllegalArgumentException("invalid split state");
        }
        if (max == minUnit) {
            return minUnit;
        }
        return minUnit + rng.nextLong(max - minUnit + 1);
    }

    public static List<Long> luckySplit(long total, int count, long minUnit, Random rng) {
        validateLuckySend(total, count);
        List<Long> parts = new ArrayList<>();
        long remain = total;
        int remainCount = count;
        for (int i = 0; i < count - 1; i++) {
            long draw = drawLuckyAmount(remain, remainCount, minUnit, rng);
            parts.add(draw);
            remain -= draw;
            remainCount--;
        }
        parts.add(remain);
        return parts;
    }
}
