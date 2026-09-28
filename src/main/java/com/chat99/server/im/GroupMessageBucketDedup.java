package com.chat99.server.im;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 群消息看板去重：仅已知三段数字 MsgId 进 v1 小时桶。
 * 过期时间在桶首次创建时写成绝对 EXPIREAT，后续 SADD 不续期。
 */
@Component
public class GroupMessageBucketDedup {

    static final int MAX_FUTURE_SKEW_SECONDS = 86_400;
    static final int BUCKET_SECONDS = 3_600;
    static final String BUCKET_VERSION = "v1";
    static final String KEY_PREFIX = "push:im:dedup:dash:group:" + BUCKET_VERSION + ":";

    private static final Pattern MSG_ID = Pattern.compile("^[0-9]+-[0-9]+-[0-9]+$");

    private static final DefaultRedisScript<Long> MARK_SCRIPT = new DefaultRedisScript<>();

    static {
        MARK_SCRIPT.setResultType(Long.class);
        MARK_SCRIPT.setScriptText("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIREAT', KEYS[1], ARGV[2])
            end
            return added
            """);
    }

    private final StringRedisTemplate redis;
    private final GroupMessageDedupProperties properties;

    public GroupMessageBucketDedup(StringRedisTemplate redis, GroupMessageDedupProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public int ttlHours() {
        return properties.ttlHours();
    }

    /**
     * @return true 表示该 member 第一次进入桶
     */
    public boolean markIfNew(String groupId, String msgId, long msgTimestamp) {
        long bucket = msgTimestamp / BUCKET_SECONDS;
        long expireAt = bucket * BUCKET_SECONDS + BUCKET_SECONDS + (long) properties.ttlHours() * BUCKET_SECONDS;
        String key = KEY_PREFIX + bucket;
        String member = groupId + "|" + msgId;
        Long added = redis.execute(MARK_SCRIPT, List.of(key), member, Long.toString(expireAt));
        return Long.valueOf(1L).equals(added);
    }

    static Long timestampIfAccepted(String msgId, long nowEpochSeconds, int ttlHours) {
        if (msgId == null || msgId.isBlank() || !MSG_ID.matcher(msgId).matches()) {
            return null;
        }
        String[] parts = msgId.split("-", -1);
        long msgTimestamp;
        try {
            msgTimestamp = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (msgTimestamp > nowEpochSeconds + MAX_FUTURE_SKEW_SECONDS) {
            return null;
        }
        long bucket = msgTimestamp / BUCKET_SECONDS;
        long expireAt = bucket * BUCKET_SECONDS + BUCKET_SECONDS + (long) ttlHours * BUCKET_SECONDS;
        if (expireAt <= nowEpochSeconds) {
            return null;
        }
        return msgTimestamp;
    }
}
