package com.chat99.server.chatattachment;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ChatAttachmentRateLimiter {

    private final StringRedisTemplate redis;
    private final ChatAttachmentProperties props;

    public ChatAttachmentRateLimiter(StringRedisTemplate redis, ChatAttachmentProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void checkInit(String userId) {
        check("init", userId, props.rateLimit().initPerMinute());
    }

    public void checkPartUrls(String userId) {
        int limit = props.rateLimit().partUrlsPerMinute();
        if (limit <= 0) {
            return;
        }
        check("part-urls", userId, limit);
    }

    public void checkStatus(String userId) {
        check("status", userId, props.rateLimit().statusPerMinute());
    }

    public void checkComplete(String userId) {
        check("complete", userId, props.rateLimit().completePerMinute());
    }

    public void checkAccess(String userId) {
        check("access", userId, props.rateLimit().accessPerMinute());
    }

    public void checkThumbnail(String userId) {
        check("thumb", userId, props.rateLimit().initPerMinute());
    }

    private void check(String op, String userId, int limit) {
        String key = "chat-att:rl:" + op + ":" + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(60));
        }
        if (count != null && count > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        }
    }
}
