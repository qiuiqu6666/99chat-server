package com.chat99.server.push;

import com.chat99.server.call.CallUserIdNormalizer;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class PushDisplayNameResolver {

    private final ImAdminClient imAdmin;
    private final UserRepository userRepository;
    private final ImUserIdService imUserIdService;

    public PushDisplayNameResolver(ImAdminClient imAdmin,
                                   UserRepository userRepository,
                                   ImUserIdService imUserIdService) {
        this.imAdmin = imAdmin;
        this.userRepository = userRepository;
        this.imUserIdService = imUserIdService;
    }

    /**
     * 被叫视角下的主叫展示名：好友备注名优先，否则平台昵称，最后回退 userId。
     */
    public String resolveCallerDisplayName(String calleeId, String callerId) {
        callerId = CallUserIdNormalizer.normalize(callerId);
        calleeId = CallUserIdNormalizer.normalize(calleeId);
        if (callerId.isBlank()) {
            return "";
        }
        if (!calleeId.isBlank()) {
            var friendName = imAdmin.resolveFriendDisplayName(
                imUserIdService.toIm(calleeId), imUserIdService.toIm(callerId));
            if (friendName.isPresent() && !friendName.get().isBlank()) {
                return friendName.get();
            }
        }
        return userRepository.findByUserId(callerId)
            .map(User::getNickname)
            .filter(n -> n != null && !n.isBlank())
            .orElse(callerId);
    }
}
