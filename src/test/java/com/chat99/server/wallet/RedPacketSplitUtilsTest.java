package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RedPacketSplitUtilsTest {

    @Test
    void luckyMaxDraw_capsAtDoubleAverage() {
        assertThat(RedPacketSplitUtils.luckyMaxDraw(10_000, 10, 1)).isEqualTo(2_000);
        assertThat(RedPacketSplitUtils.luckyMaxDraw(100, 3, 1)).isEqualTo(66);
    }

    @Test
    void luckyMaxDraw_safeMaxWhenRemainderTight() {
        assertThat(RedPacketSplitUtils.luckyMaxDraw(5, 5, 1)).isEqualTo(1);
        assertThat(RedPacketSplitUtils.luckyMaxDraw(10, 9, 1)).isEqualTo(2);
    }

    @Test
    void validateLuckySend_rejectsInsufficientTotal() {
        assertThatThrownBy(() -> RedPacketSplitUtils.validateLuckySend(5, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateLuckySend_rejectsExcessiveCount() {
        assertThatThrownBy(() -> RedPacketSplitUtils.validateLuckySend(200, 101))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void luckySplit_preservesTotalAndMinimum() {
        Random rng = new Random(42);
        List<Long> parts = RedPacketSplitUtils.luckySplit(10_000, 10, 1, rng);
        assertThat(parts).hasSize(10);
        assertThat(parts.stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000);
        assertThat(parts).allMatch(p -> p >= 1);
    }

    @Test
    void drawLuckyAmount_firstDrawNeverExceedsDoubleAverage() {
        for (int i = 0; i < 200; i++) {
            long amount = RedPacketSplitUtils.drawLuckyAmount(10_000, 10, 1, new Random());
            assertThat(amount).isBetween(1L, 2_000L);
        }
    }

    @Test
    void drawLuckyAmount_trueRandomIsNotPacketIdDeterministic() {
        java.util.HashSet<Long> distinct = new java.util.HashSet<>();
        for (int i = 0; i < 80; i++) {
            distinct.add(RedPacketSplitUtils.drawLuckyAmount(10_000, 10, 1, new Random()));
        }
        assertThat(distinct.size()).isGreaterThan(1);
    }
}
