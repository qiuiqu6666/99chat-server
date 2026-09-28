package com.chat99.server.group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 群成员本地集合。领取热路径只做 SISMEMBER，不加腾讯。 */
@Component
public class GroupMemberRedisSet {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberRedisSet.class);

    public static String key(String groupId) {
        return "group:" + groupId + ":members";
    }

    private static final DefaultRedisScript<List> SSCAN = new DefaultRedisScript<>("""
        return redis.call('SSCAN', KEYS[1], ARGV[1], 'COUNT', ARGV[2])
        """, List.class);

    private final StringRedisTemplate redis;

    public GroupMemberRedisSet(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record ScanPage(String nextCursor, List<String> members) {}

    /** 一页成员。cursor 传 "0" 表示从头开始。不提供一次取全员的方法。 */
    public ScanPage scan(String groupId, String cursor, int count) {
        if (groupId == null || groupId.isBlank()) {
            return new ScanPage("0", List.of());
        }
        int size = count <= 0 ? 500 : count;
        String start = cursor == null || cursor.isBlank() ? "0" : cursor;
        List<?> raw = redis.execute(SSCAN, List.of(key(groupId.trim())), start, String.valueOf(size));
        if (raw == null || raw.size() < 2) {
            return new ScanPage("0", List.of());
        }
        String next = String.valueOf(raw.get(0));
        List<String> members = new ArrayList<>();
        Object page = raw.get(1);
        if (page instanceof List<?> ids) {
            for (Object id : ids) {
                if (id != null && !String.valueOf(id).isBlank()) {
                    members.add(String.valueOf(id).trim());
                }
            }
        }
        return new ScanPage(next, members);
    }

    public void add(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.opsForSet().add(key(groupId.trim()), userId.trim());
        } catch (RuntimeException e) {
            log.warn("group member redis add failed groupId={} err={}", groupId, e.getMessage());
        }
    }

    public boolean contains(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.opsForSet().isMember(key(groupId.trim()), userId.trim()));
        } catch (RuntimeException e) {
            log.warn("group member redis contains failed groupId={} err={}", groupId, e.getMessage());
            return true;
        }
    }

    public void remove(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.opsForSet().remove(key(groupId.trim()), userId.trim());
        } catch (RuntimeException e) {
            log.warn("group member redis remove failed groupId={} err={}", groupId, e.getMessage());
        }
    }

    public void removeAll(String groupId, Collection<String> userIds) {
        if (groupId == null || groupId.isBlank() || userIds == null || userIds.isEmpty()) {
            return;
        }
        String[] ids = userIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toArray(String[]::new);
        if (ids.length == 0) {
            return;
        }
        try {
            redis.opsForSet().remove(key(groupId.trim()), (Object[]) ids);
        } catch (RuntimeException e) {
            log.warn("group member redis removeAll failed groupId={} err={}", groupId, e.getMessage());
        }
    }

    public void delete(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(groupId.trim()));
        } catch (RuntimeException e) {
            log.warn("group member redis delete failed groupId={} err={}", groupId, e.getMessage());
        }
    }

    public void addAll(String groupId, Collection<String> userIds) {
        if (groupId == null || groupId.isBlank() || userIds == null || userIds.isEmpty()) {
            return;
        }
        String[] ids = userIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toArray(String[]::new);
        if (ids.length == 0) {
            return;
        }
        try {
            redis.opsForSet().add(key(groupId.trim()), ids);
        } catch (RuntimeException e) {
            log.warn("group member redis addAll failed groupId={} err={}", groupId, e.getMessage());
        }
    }
}
