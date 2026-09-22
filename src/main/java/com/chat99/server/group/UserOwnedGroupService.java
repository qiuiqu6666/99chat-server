package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserOwnedGroupService {

    private static final Logger log = LoggerFactory.getLogger(UserOwnedGroupService.class);

    private final UserOwnedGroupRepository repository;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final GroupCreateLimitConfigService config;

    public UserOwnedGroupService(UserOwnedGroupRepository repository,
                                 ImAdminClient imAdmin,
                                 ImUserIdService imUserIdService,
                                 GroupCreateLimitConfigService config) {
        this.repository = repository;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.config = config;
    }

    public record GroupCreateQuota(int max, int used, int remaining) {}

    public GroupCreateQuota quota(String userId, String groupType) {
        return quotas(userId, List.of(groupType)).get(groupType);
    }

    /**
     * 同请求批量取额度；IM fallback 开启时只打一轮 joined list / group info。
     */
    public Map<String, GroupCreateQuota> quotas(String userId, Collection<String> groupTypes) {
        Map<String, GroupCreateQuota> out = new LinkedHashMap<>();
        if (groupTypes == null || groupTypes.isEmpty()) {
            return out;
        }
        List<String> types = groupTypes.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(String::trim)
            .distinct()
            .toList();

        Map<String, Integer> imCounts = Map.of();
        if (config.isUseImCountFallback()) {
            imCounts = imAdmin.countOwnedGroupsByTypes(imUserIdService.toIm(userId), types);
        }

        for (String type : types) {
            int max = config.limitForType(type);
            if (max < 0) {
                out.put(type, new GroupCreateQuota(-1, 0, -1));
                continue;
            }
            long local = repository.countByOwnerUserIdAndGroupType(userId, type);
            int im = imCounts.getOrDefault(type, 0);
            int used = config.isUseImCountFallback() ? (int) Math.max(local, im) : (int) local;
            out.put(type, new GroupCreateQuota(max, used, Math.max(0, max - used)));
        }
        return out;
    }

    public boolean canCreate(String userId, String groupType) {
        if (!config.isEnabled()) {
            return true;
        }
        int limit = config.limitForType(groupType);
        if (limit < 0) {
            return true;
        }
        return effectiveOwnedCount(userId, groupType) < limit;
    }

    public int effectiveOwnedCount(String userId, String groupType) {
        GroupCreateQuota q = quota(userId, groupType);
        return q == null ? 0 : Math.max(0, q.used());
    }

    @Transactional
    public void recordCreated(String ownerUserId, String groupType, String groupId) {
        if (ownerUserId == null || groupType == null || groupId == null) {
            return;
        }
        if (config.limitForType(groupType) < 0) {
            return;
        }
        if (repository.existsById(groupId)) {
            return;
        }
        UserOwnedGroup row = new UserOwnedGroup();
        row.setGroupId(groupId);
        row.setOwnerUserId(ownerUserId);
        row.setGroupType(groupType.trim());
        repository.save(row);
        log.info("user owned group recorded owner={} type={} groupId={}", ownerUserId, groupType, groupId);
    }

    @Transactional
    public void recordDestroyed(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        if (repository.existsById(groupId)) {
            repository.deleteById(groupId);
            log.info("user owned group removed groupId={}", groupId);
        }
    }
}
