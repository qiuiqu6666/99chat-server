package com.chat99.server.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GroupMessageBucketDedupTest {

    private static final long NOW = 1_790_263_559L;
    private static final int TTL_HOURS = 48;

    @Test
    void acceptsKnownTencentMsgId() {
        String msgId = "144115249996004533-1790263559-68427540";
        Long ts = GroupMessageBucketDedup.timestampIfAccepted(msgId, NOW, TTL_HOURS);
        assertEquals(1_790_263_559L, ts);
    }

    @Test
    void rejectsAncientTimestamp() {
        assertNull(GroupMessageBucketDedup.timestampIfAccepted("123-1-456", NOW, TTL_HOURS));
    }

    @Test
    void rejectsTimestampBeyondOneDayInTheFuture() {
        long future = NOW + GroupMessageBucketDedup.MAX_FUTURE_SKEW_SECONDS + 1;
        assertNull(GroupMessageBucketDedup.timestampIfAccepted("1-" + future + "-1", NOW, TTL_HOURS));
    }

    @Test
    void acceptsTimestampInsideFutureSkew() {
        long future = NOW + GroupMessageBucketDedup.MAX_FUTURE_SKEW_SECONDS;
        assertEquals(future, GroupMessageBucketDedup.timestampIfAccepted("1-" + future + "-1", NOW, TTL_HOURS));
    }

    @Test
    void rejectsNonThreePartMsgId() {
        assertNull(GroupMessageBucketDedup.timestampIfAccepted("not-a-msg", NOW, TTL_HOURS));
        assertNull(GroupMessageBucketDedup.timestampIfAccepted("12-34", NOW, TTL_HOURS));
        assertNull(GroupMessageBucketDedup.timestampIfAccepted("12-x-34", NOW, TTL_HOURS));
    }

    @Test
    void rejectsMiddleSegmentThatOverflowsLong() {
        String overflow = "1-999999999999999999999-1";
        assertNull(GroupMessageBucketDedup.timestampIfAccepted(overflow, NOW, TTL_HOURS));
    }
}
