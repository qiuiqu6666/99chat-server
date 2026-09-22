package com.chat99.server.user;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserFriendMutualCache {

    private static final Duration MUTUAL_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final Duration relationTtl;

    public UserFriendMutualCache(
            StringRedisTemplate redis,
            @Value("${chat99.friend.relation-cache-ttl-seconds:15}") long relationTtlSeconds) {
        this.redis = redis;
        this.relationTtl = Duration.ofSeconds(Math.max(1L, relationTtlSeconds));
    }

    public Boolean getMutual(String userA, String userB) {
        String key = mutualKey(userA, userB);
        String val = redis.opsForValue().get(key);
        if (val == null) {
            return null;
        }
        return "1".equals(val);
    }

    public void putMutual(String userA, String userB, boolean mutual) {
        redis.opsForValue().set(mutualKey(userA, userB), mutual ? "1" : "0", MUTUAL_TTL);
    }

    /**
     * 短缓存非对称关系：{@code inMyFriendList}/{@code inTheirFriendList}，编码为 {@code "10"} / {@code "11"} 等。
     */
    public Optional<RelationEdges> getRelationEdges(String viewerUserId, String peerUserId) {
        if (viewerUserId == null || peerUserId == null) {
            return Optional.empty();
        }
        try {
            String val = redis.opsForValue().get(relationKey(viewerUserId, peerUserId));
            if (val == null || val.length() < 2) {
                return Optional.empty();
            }
            return Optional.of(new RelationEdges(val.charAt(0) == '1', val.charAt(1) == '1'));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void putRelationEdges(String viewerUserId, String peerUserId, boolean inMy, boolean inTheir) {
        if (viewerUserId == null || peerUserId == null) {
            return;
        }
        try {
            String packed = (inMy ? "1" : "0") + (inTheir ? "1" : "0");
            redis.opsForValue().set(relationKey(viewerUserId, peerUserId), packed, relationTtl);
            putMutual(viewerUserId, peerUserId, inMy && inTheir);
        } catch (Exception ignored) {
            // 缓存失败不影响主路径
        }
    }

    public void evict(String userA, String userB) {
        redis.delete(mutualKey(userA, userB));
        redis.delete(relationKey(userA, userB));
        redis.delete(relationKey(userB, userA));
    }

    public record RelationEdges(boolean inMyFriendList, boolean inTheirFriendList) {}

    private static String mutualKey(String a, String b) {
        if (a.compareTo(b) <= 0) {
            return "friend:mutual:" + a + ":" + b;
        }
        return "friend:mutual:" + b + ":" + a;
    }

    private static String relationKey(String viewer, String peer) {
        return "friend:rel:" + viewer + ":" + peer;
    }
}
