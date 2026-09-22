package com.chat99.server.user;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FriendRequestRateLimiter {

    private final StringRedisTemplate redis;
    private final UserFriendProperties props;

    public FriendRequestRateLimiter(StringRedisTemplate redis, UserFriendProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 同一对用户发起 pending 申请前检查；通过则写入冷却键。 */
    public void checkAndMark(String fromUserId, String toUserId) {
        String key = "friend:req:cooldown:" + fromUserId + ":" + toUserId;
        Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(props.requestCooldownSeconds()));
        if (Boolean.FALSE.equals(ok)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "FRIEND_REQUEST_COOLDOWN");
        }
    }

    public void clear(String fromUserId, String toUserId) {
        redis.delete("friend:req:cooldown:" + fromUserId + ":" + toUserId);
    }
}
