package com.chat99.server.group;

import com.chat99.server.im.ImUserIdService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupJoinLimitService {

    public static final String LIMIT_TYPE_JOIN = "join";
    public static final String LIMIT_TYPE_COMMUNITY_JOIN = "communityJoin";
    public static final String LIMIT_TYPE_COMMUNITY_CREATE = "communityCreate";
    public static final String CODE_JOIN_EXCEEDED = "GROUP_JOIN_LIMIT_EXCEEDED";
    public static final String CODE_COMMUNITY_CREATE = "GROUP_CREATE_LIMIT_COMMUNITY";

    public record JoinQuota(int max, int used, int remaining, boolean limited) {}

    private final GroupMemberRepository memberRepository;
    private final GroupCreateLimitConfigService config;
    private final UserOwnedGroupService ownedGroupService;
    private final ImUserIdService imUserIdService;

    public GroupJoinLimitService(GroupMemberRepository memberRepository,
                                 GroupCreateLimitConfigService config,
                                 UserOwnedGroupService ownedGroupService,
                                 ImUserIdService imUserIdService) {
        this.memberRepository = memberRepository;
        this.config = config;
        this.ownedGroupService = ownedGroupService;
        this.imUserIdService = imUserIdService;
    }

    public JoinQuota nonCommunityJoinQuota(String userId) {
        int max = config.getMaxJoinGroups();
        if (max < 0) {
            return new JoinQuota(-1, 0, -1, false);
        }
        int used = (int) memberRepository.countNonCommunityGroupsByUserId(imUserIdService.toIm(userId));
        return new JoinQuota(max, used, Math.max(0, max - used), true);
    }

    public JoinQuota communityJoinQuota(String userId) {
        int max = config.getMaxCommunityJoinGroups();
        if (max < 0) {
            return new JoinQuota(-1, 0, -1, false);
        }
        int used = (int) memberRepository.countCommunityGroupsByUserId(imUserIdService.toIm(userId));
        return new JoinQuota(max, used, Math.max(0, max - used), true);
    }

    public JoinQuota communityCreateQuota(String userId) {
        UserOwnedGroupService.GroupCreateQuota q = ownedGroupService.quota(userId, "Community");
        if (q == null || q.max() < 0) {
            return new JoinQuota(-1, 0, -1, false);
        }
        return new JoinQuota(q.max(), q.used(), q.remaining(), true);
    }

    /**
     * 建群前：Community 先校验创建额度，再校验候选用户加入额度；其它类型只校验加入。
     */
    public void assertCanCreateGroup(String ownerUserId, String groupType, Collection<String> memberUserIds) {
        if (!config.isEnabled() || !config.isEnforce()) {
            return;
        }
        Set<String> candidates = new LinkedHashSet<>();
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            candidates.add(ownerUserId.trim());
        }
        if (memberUserIds != null) {
            for (String id : memberUserIds) {
                if (id != null && !id.isBlank()) {
                    candidates.add(id.trim());
                }
            }
        }

        if (GroupCreateLimitConfigService.isCommunity(groupType)) {
            if (!ownedGroupService.canCreate(ownerUserId, "Community")) {
                JoinQuota createQ = communityCreateQuota(ownerUserId);
                throw new GroupJoinLimitExceededException(
                    CODE_COMMUNITY_CREATE,
                    List.of(new GroupJoinLimitExceededException.OverLimitUser(
                        ownerUserId, createQ.used(), createQ.max(), LIMIT_TYPE_COMMUNITY_CREATE)));
            }
        }

        List<GroupJoinLimitExceededException.OverLimitUser> over =
            findOverLimitUsers(candidates, groupType);
        if (!over.isEmpty()) {
            throw new GroupJoinLimitExceededException(CODE_JOIN_EXCEEDED, over);
        }
    }

    /**
     * 加群前批量校验；{@code alreadyMemberUserIds} 中的用户跳过（无需 +1）。
     */
    public void assertUsersCanJoin(Collection<String> userIds,
                                   String groupType,
                                   Collection<String> alreadyMemberUserIds) {
        if (!config.isEnabled() || !config.isEnforce()) {
            return;
        }
        Set<String> skip = alreadyMemberUserIds == null
            ? Set.of()
            : alreadyMemberUserIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> candidates = new LinkedHashSet<>();
        if (userIds != null) {
            for (String id : userIds) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                String trimmed = id.trim();
                if (!skip.contains(trimmed)) {
                    candidates.add(trimmed);
                }
            }
        }
        List<GroupJoinLimitExceededException.OverLimitUser> over =
            findOverLimitUsers(candidates, groupType);
        if (!over.isEmpty()) {
            throw new GroupJoinLimitExceededException(CODE_JOIN_EXCEEDED, over);
        }
    }

    public List<GroupJoinLimitExceededException.OverLimitUser> findOverLimitUsers(
            Collection<String> userIds, String groupType) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        if (!config.isEnabled()) {
            return List.of();
        }
        int max = config.joinLimitForGroupType(groupType);
        if (max < 0) {
            return List.of();
        }
        boolean community = GroupCreateLimitConfigService.isCommunity(groupType);
        String limitType = community ? LIMIT_TYPE_COMMUNITY_JOIN : LIMIT_TYPE_JOIN;
        Map<String, Integer> usedByUser = batchUsed(userIds, community);
        List<GroupJoinLimitExceededException.OverLimitUser> out = new ArrayList<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            String businessUserId = imUserIdService.toBusinessForDisplay(userId.trim());
            int used = usedByUser.getOrDefault(businessUserId, 0);
            if (used >= max) {
                out.add(new GroupJoinLimitExceededException.OverLimitUser(
                    businessUserId, used, max, limitType));
            }
        }
        return out;
    }

    /** IM 回调用：是否允许（考虑 enabled/enforce/logOnly）。 */
    public boolean isJoinAllowed(Collection<String> userIds, String groupType) {
        if (!config.isEnabled()) {
            return true;
        }
        List<GroupJoinLimitExceededException.OverLimitUser> over =
            findOverLimitUsers(userIds, groupType);
        if (over.isEmpty()) {
            return true;
        }
        if (config.isLogOnly() && !config.isEnforce()) {
            return true;
        }
        return !config.isEnforce();
    }

    public String joinRejectCode(String groupType) {
        return GroupCreateLimitConfigService.isCommunity(groupType)
            ? "GROUP_JOIN_LIMIT_COMMUNITY"
            : "GROUP_JOIN_LIMIT";
    }

    private Map<String, Integer> batchUsed(Collection<String> userIds, boolean community) {
        List<String> ids = userIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
        Map<String, Integer> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        Map<String, String> imToBusiness = new HashMap<>();
        List<String> imIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            String businessUserId = imUserIdService.toBusinessForDisplay(id);
            String imId = imUserIdService.toIm(businessUserId);
            out.putIfAbsent(businessUserId, 0);
            imIds.add(imId);
            imToBusiness.put(imId, businessUserId);
        }
        List<Object[]> rows = community
            ? memberRepository.countCommunityGroupsByUserIds(imIds)
            : memberRepository.countNonCommunityGroupsByUserIds(imIds);
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            String key = row[0].toString();
            String businessUserId = imToBusiness.getOrDefault(key, key);
            out.put(businessUserId, ((Number) row[1]).intValue());
        }
        return out;
    }

    /** 兼容旧抛法：无名单时用 ResponseStatusException。 */
    public static ResponseStatusException forbidden(String code) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, code);
    }
}
