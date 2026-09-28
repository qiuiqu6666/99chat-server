package com.chat99.server.im;

import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 群消息推送流。入队用 Lua 原子判断长度，满则拒绝写入。 */
@Component
public class GroupPushStream {

    public static final String NORMAL = "push:group:after-send";
    public static final String PRIORITY = "push:group:priority";
    public static final String GROUP = "push:group:workers";
    public static final int NORMAL_LIMIT = 10_000;
    public static final int PRIORITY_LIMIT = 2_000;

    private static final DefaultRedisScript<String> ENQUEUE = new DefaultRedisScript<>("""
        local n = redis.call('XLEN', KEYS[1])
        if n >= tonumber(ARGV[1]) then
          return 'FULL'
        end
        local args = {'XADD', KEYS[1], '*'}
        for i = 2, #ARGV do
          args[#args + 1] = ARGV[i]
        end
        return redis.call(unpack(args))
        """, String.class);

    private final StringRedisTemplate redis;

    public GroupPushStream(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueue(boolean priority, Map<String, String> fields) {
        String key = priority ? PRIORITY : NORMAL;
        int limit = priority ? PRIORITY_LIMIT : NORMAL_LIMIT;
        String[] args = new String[1 + fields.size() * 2];
        args[0] = String.valueOf(limit);
        int i = 1;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            args[i++] = field.getKey();
            args[i++] = field.getValue() == null ? "" : field.getValue();
        }
        String result = redis.execute(ENQUEUE, List.of(key), (Object[]) args);
        if ("FULL".equals(result)) {
            throw new GroupPushStreamFullException(key);
        }
    }
}
