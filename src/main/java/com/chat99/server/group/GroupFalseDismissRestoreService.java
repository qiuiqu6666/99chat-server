package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import com.chat99.server.im.ImGroupFetchResult;
import com.chat99.server.im.ImGroupInfoBatchResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 修复「本地 dismissed=1 但腾讯 IM 群仍存活」的假解散投影。
 */
@Service
public class GroupFalseDismissRestoreService {

    private static final Logger log = LoggerFactory.getLogger(GroupFalseDismissRestoreService.class);
    private static final int IM_INFO_BATCH = 20;
    private static final int SCAN_PAGE = 100;

    private final GroupProfileRepository profileRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupProjectionService projection;
    private final ImAdminClient im;

    public GroupFalseDismissRestoreService(GroupProfileRepository profileRepository,
                                           GroupMemberRepository memberRepository,
                                           GroupProjectionService projection,
                                           ImAdminClient im) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
        this.projection = projection;
        this.im = im;
    }

    public record RestoreItem(
        String groupId,
        String reason,
        boolean dismissedBefore,
        long localMemberCount,
        int memberRowsAfter,
        boolean restored) {}

    public record RestoreReport(
        boolean applied,
        int candidateCount,
        int restoredCount,
        List<RestoreItem> items) {}

    public RestoreReport dryRunByGroupIds(List<String> groupIds) {
        return run(normalizeIds(groupIds), false);
    }

    @Transactional
    public RestoreReport applyByGroupIds(List<String> groupIds) {
        return run(normalizeIds(groupIds), true);
    }

    public RestoreReport dryRunAllLocallyDismissed() {
        return run(findLocallyDismissedImAliveCandidates(), false);
    }

    @Transactional
    public RestoreReport applyAllLocallyDismissedImAlive() {
        return run(findLocallyDismissedImAliveCandidates(), true);
    }

    /** 扫本地 dismissed，批查 IM，返回仍存活的 groupId。 */
    public List<String> findLocallyDismissedImAliveCandidates() {
        List<String> dismissedIds = new ArrayList<>();
        int page = 0;
        while (true) {
            Page<GroupProfile> slice = profileRepository.findByDismissedTrue(PageRequest.of(page, SCAN_PAGE));
            for (GroupProfile p : slice) {
                dismissedIds.add(p.getGroupId());
            }
            if (!slice.hasNext()) {
                break;
            }
            page++;
            if (page > 10_000) {
                break;
            }
        }
        return filterImAlive(dismissedIds);
    }

    private RestoreReport run(List<String> groupIds, boolean apply) {
        List<RestoreItem> items = new ArrayList<>();
        int restored = 0;
        for (String groupId : groupIds) {
            boolean dismissed = projection.isLocallyDismissed(groupId);
            long members = memberRepository.countByGroupId(groupId);
            ImGroupFetchResult fetch = im.fetchGroupAdminInfoResult(groupId);
            String reason;
            if (fetch.isDefinitelyGone()) {
                reason = "IM_GONE";
            } else if (fetch.isTransientFailure() || !fetch.isOk()) {
                reason = "IM_TEMP_UNAVAILABLE";
            } else if (!dismissed) {
                reason = "NOT_DISMISSED";
            } else {
                reason = "IM_ALIVE";
            }
            int after = 0;
            boolean did = false;
            if (apply && "IM_ALIVE".equals(reason)) {
                GroupProjectionService.RestoreResult result =
                    projection.restoreDismissedGroupFromImIfAlive(groupId);
                did = result.restored();
                after = result.memberRows();
                reason = result.reason();
                if (did) {
                    restored++;
                }
            }
            items.add(new RestoreItem(groupId, reason, dismissed, members, after, did));
            log.info("false-dismiss {} groupId={} reason={} dismissed={} members={}",
                apply ? "apply" : "dry-run", groupId, reason, dismissed, members);
        }
        return new RestoreReport(apply, items.size(), restored, items);
    }

    private List<String> filterImAlive(List<String> groupIds) {
        List<String> alive = new ArrayList<>();
        for (int i = 0; i < groupIds.size(); i += IM_INFO_BATCH) {
            List<String> batch = groupIds.subList(i, Math.min(i + IM_INFO_BATCH, groupIds.size()));
            ImGroupInfoBatchResult batchResult = im.fetchGroupAdminInfoBatch(batch);
            if (batchResult.isUnreliable()) {
                // 不可靠时逐个 Result 判定，避免漏修
                for (String gid : batch) {
                    ImGroupFetchResult one = im.fetchGroupAdminInfoResult(gid);
                    if (one.isOk()) {
                        alive.add(gid);
                    }
                }
                continue;
            }
            Map<String, GroupAdminInfo> found = batchResult.found();
            for (String gid : batch) {
                if (found.containsKey(gid)) {
                    alive.add(gid);
                }
            }
        }
        return alive;
    }

    private static List<String> normalizeIds(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : groupIds) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }
}
