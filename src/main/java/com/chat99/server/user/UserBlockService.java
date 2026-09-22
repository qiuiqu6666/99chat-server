package com.chat99.server.user;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserBlockService {

    public record BlockItem(String userId, String nickname, String avatarUrl, Long blockedAt) {}

    public record BlockListResponse(List<BlockItem> items, int startIndex, boolean hasMore) {}

    private final ImAdminClient im;
    private final UserRepository userRepository;

    public UserBlockService(ImAdminClient im, UserRepository userRepository) {
        this.im = im;
        this.userRepository = userRepository;
    }

    public void block(String fromUserId, String targetUserId) {
        String target = requirePeerUserId(fromUserId, targetUserId);
        requireUserExists(target);
        im.addBlackList(fromUserId, target);
    }

    public void unblock(String fromUserId, String targetUserId) {
        String target = requirePeerUserId(fromUserId, targetUserId);
        im.deleteBlackList(fromUserId, target);
    }

    public BlockListResponse list(String fromUserId, int startIndex, int limit) {
        int start = Math.max(startIndex, 0);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        ImAdminClient.BlackListPage page = im.listBlackList(fromUserId, start, safeLimit);
        List<String> ids = page.items().stream().map(ImAdminClient.BlackListEntry::userId).toList();
        Map<String, User> usersById = userRepository.findByUserIdIn(ids).stream()
            .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));
        List<BlockItem> items = page.items().stream()
            .map(entry -> {
                User user = usersById.get(entry.userId());
                return new BlockItem(
                    entry.userId(),
                    user == null ? null : user.getNickname(),
                    user == null ? null : user.getAvatarUrl(),
                    toEpochMillis(entry.addTimeSec()));
            })
            .toList();
        int nextStartIndex = page.nextStartIndex();
        return new BlockListResponse(items, nextStartIndex, nextStartIndex > 0);
    }

    /**
     * IM 未配置视为未拉黑；已配置但检查失败抛 503 {@code IM_UNAVAILABLE}。
     */
    public boolean isEitherBlocked(String userA, String userB) {
        if (userA == null || userB == null || userA.isBlank() || userB.isBlank() || userA.equals(userB)) {
            return false;
        }
        if (!im.isConfigured()) {
            return false;
        }
        try {
            return im.isEitherInBlackList(userA, userB);
        } catch (ImRestException e) {
            if ("IM_NOT_CONFIGURED".equals(e.getMessage())) {
                return false;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IM_UNAVAILABLE", e);
        }
    }

    private void requireUserExists(String userId) {
        userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private static String requirePeerUserId(String fromUserId, String targetUserId) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String target = targetUserId.trim();
        if (fromUserId != null && fromUserId.equals(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return target;
    }

    private static Long toEpochMillis(Long addTimeSec) {
        if (addTimeSec == null || addTimeSec <= 0) {
            return null;
        }
        return addTimeSec * 1000L;
    }
}
