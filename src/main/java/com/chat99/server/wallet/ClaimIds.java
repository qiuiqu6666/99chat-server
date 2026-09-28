package com.chat99.server.wallet;

/**
 * 领取业务号。抢到份额时生成，HTTP、Redis、MySQL 使用同一个值。
 */
public final class ClaimIds {

    private static final long EPOCH_MS = 1_704_067_200_000L;
    private static final long WORKER = 1L;
    private static long lastMs = -1L;
    private static long sequence;

    private ClaimIds() {}

    public static synchronized long next() {
        long now = System.currentTimeMillis();
        if (now == lastMs) {
            sequence = (sequence + 1) & 4095L;
            if (sequence == 0) {
                while (now <= lastMs) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
            lastMs = now;
        }
        return ((now - EPOCH_MS) << 22) | (WORKER << 12) | sequence;
    }
}
