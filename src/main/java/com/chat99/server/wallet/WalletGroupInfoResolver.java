package com.chat99.server.wallet;

import com.chat99.server.group.GroupAvatarDefaults;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 钱包记录关联群资料（群名/群头像）批量解析：直接查 group_profile 表，
 * 不访问 IM 侧，减少网络请求、快速返回。
 * 含已解散群（历史记录仍需展示群名/群头像）；查不到返回空 Map。
 */
@Service
public class WalletGroupInfoResolver {

    /** 群公开资料（群名 + 群头像 URL，均来自数据库；无自定义头像时回退默认群头像）。 */
    public record GroupBrief(String groupId, String groupName, String groupAvatar) {}

    private final GroupProfileRepository groupProfileRepository;
    private final GroupAvatarDefaults groupAvatarDefaults;

    public WalletGroupInfoResolver(GroupProfileRepository groupProfileRepository,
                                   GroupAvatarDefaults groupAvatarDefaults) {
        this.groupProfileRepository = groupProfileRepository;
        this.groupAvatarDefaults = groupAvatarDefaults;
    }

    /** 批量取群名+群头像；入参为空或查询异常时返回空 Map。 */
    public Map<String, GroupBrief> resolveGroups(Collection<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        Map<String, GroupBrief> out = new HashMap<>();
        try {
            Map<String, GroupProfile> byGroupId = new HashMap<>();
            for (GroupProfile g : groupProfileRepository.findAllById(groupIds)) {
                if (g != null && g.getGroupId() != null) {
                    byGroupId.put(g.getGroupId(), g);
                }
            }
            for (String gid : groupIds) {
                if (gid == null || gid.isBlank()) {
                    continue;
                }
                GroupProfile g = byGroupId.get(gid.trim());
                if (g != null) {
                    String name = g.getGroupName();
                    if (name == null || name.isBlank()) {
                        name = g.getDisplayAlias();
                    }
                    out.put(gid, new GroupBrief(g.getGroupId(), name, groupAvatarDefaults.resolve(g.getAvatarUrl())));
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
