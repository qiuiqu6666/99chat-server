package com.chat99.server.wallet;

import com.chat99.server.oss.OssPublicUrl;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 钱包记录涉及方（转账双方/红包发送人/收款人）昵称与头像批量解析：直接查 users 表，
 * 不访问 IM 侧，减少网络请求、快速返回。业务ID 与 users.user_id 恒等（无映射表）。
 * 查不到返回空 Map，调用方以 null 兜底（前端可用 receiverUserId 兜底查询）。
 */
@Service
public class WalletPartyNameResolver {

    /** 涉及方公开资料（昵称 + 头像 URL，均来自数据库）。 */
    public record PartyProfile(String nickname, String avatarUrl) {}

    private final UserRepository userRepository;

    public WalletPartyNameResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 批量取昵称+头像；入参为空或查询异常时返回空 Map。 */
    public Map<String, PartyProfile> resolveProfiles(Collection<String> businessUserIds) {
        if (businessUserIds == null || businessUserIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PartyProfile> out = new HashMap<>();
        try {
            // 业务ID与 users.user_id 恒等；去重后单次 IN 查询，避免 N+1
            Map<String, User> byUserId = new HashMap<>();
            for (User u : userRepository.findByUserIdIn(businessUserIds)) {
                if (u != null && u.getUserId() != null) {
                    byUserId.put(u.getUserId(), u);
                }
            }
            for (String biz : businessUserIds) {
                if (biz == null || biz.isBlank()) {
                    continue;
                }
                User u = byUserId.get(biz.trim());
                if (u != null) {
                    out.put(biz, new PartyProfile(
                        u.getNickname() == null || u.getNickname().isBlank() ? null : u.getNickname(),
                        normalizeAvatar(u.getAvatarUrl())));
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 批量取昵称；入参为空或解析异常时返回空 Map。 */
    public Map<String, String> resolveDisplayNames(Collection<String> businessUserIds) {
        Map<String, PartyProfile> profiles = resolveProfiles(businessUserIds);
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, PartyProfile> e : profiles.entrySet()) {
            if (e.getValue() != null && e.getValue().nickname() != null) {
                out.put(e.getKey(), e.getValue().nickname());
            }
        }
        return out;
    }

    private static String normalizeAvatar(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return OssPublicUrl.normalizePublicUrl(raw.trim());
    }
}
