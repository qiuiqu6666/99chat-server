package com.chat99.server.push;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PushFocusService {

    private static final String KEY_PREFIX = "push:focus:";
    private static final int FOCUS_TTL_SECONDS = 90;

    private final StringRedisTemplate redis;

    public PushFocusService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void setFocus(String userId, String chatType, String peerId, String groupId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_USER");
        }
        String encoded = encodeFocus(chatType, peerId, groupId);
        redis.opsForValue().set(KEY_PREFIX + userId.trim(), encoded, Duration.ofSeconds(FOCUS_TTL_SECONDS));
    }

    public void clearFocus(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        redis.delete(KEY_PREFIX + userId.trim());
    }

    /** 用户是否正在查看该会话（发聊天 Push 前检查）。 */
    public boolean isFocusedOnConversation(String userId, String chatType, String c2cPeerId, String groupId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String stored = redis.opsForValue().get(KEY_PREFIX + userId.trim());
        if (stored == null || stored.isBlank()) {
            return false;
        }
        return matchesFocus(stored, chatType, c2cPeerId, groupId);
    }

    /** 这一批里正在查看该群的用户。 */
    public Set<String> focusedOnGroup(Collection<String> userIds, String groupId) {
        if (userIds == null || userIds.isEmpty() || groupId == null || groupId.isBlank()) {
            return Set.of();
        }
        List<String> ids = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            String id = userId.trim();
            ids.add(id);
            keys.add(KEY_PREFIX + id);
        }
        if (keys.isEmpty()) {
            return Set.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        Set<String> focused = new HashSet<>();
        if (values == null) {
            return focused;
        }
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            String stored = values.get(i);
            if (stored != null && matchesFocus(stored, "group", null, groupId)) {
                focused.add(ids.get(i));
            }
        }
        return focused;
    }

    static String encodeFocus(String chatType, String peerId, String groupId) {
        String normalized = normalizeChatType(chatType);
        if ("c2c".equals(normalized)) {
            if (peerId == null || peerId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PEER_ID");
            }
            return "c2c:" + peerId.trim();
        }
        if ("group".equals(normalized)) {
            String gid = firstNonBlank(groupId, peerId);
            if (gid == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_ID");
            }
            return "group:" + gid.trim();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_TYPE");
    }

    static boolean matchesFocus(String stored, String chatType, String c2cPeerId, String groupId) {
        String normalized = normalizeChatType(chatType);
        if ("c2c".equals(normalized)) {
            if (c2cPeerId == null || c2cPeerId.isBlank()) {
                return false;
            }
            return stored.equals("c2c:" + c2cPeerId.trim());
        }
        if ("group".equals(normalized)) {
            if (groupId == null || groupId.isBlank()) {
                return false;
            }
            return stored.equals("group:" + groupId.trim());
        }
        return false;
    }

    private static String normalizeChatType(String chatType) {
        if (chatType == null || chatType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_TYPE");
        }
        String value = chatType.trim().toLowerCase();
        if (!"c2c".equals(value) && !"group".equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_TYPE");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
