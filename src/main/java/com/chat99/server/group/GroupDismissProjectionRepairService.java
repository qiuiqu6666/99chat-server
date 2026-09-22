package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.GroupAdminInfo;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 修复「已有解散事件 / IM 已删，但本地投影未 dismissed」的脏数据。
 */
@Service
public class GroupDismissProjectionRepairService {

    private static final Logger log = LoggerFactory.getLogger(GroupDismissProjectionRepairService.class);
    private static final int IM_INFO_BATCH = 20;

    private final GroupChangeEventRepository changeEventRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupProjectionService projection;
    private final UserOwnedGroupService ownedGroupService;
    private final ImAdminClient im;

    public GroupDismissProjectionRepairService(GroupChangeEventRepository changeEventRepository,
                                               GroupMemberRepository memberRepository,
                                               GroupProjectionService projection,
                                               UserOwnedGroupService ownedGroupService,
                                               ImAdminClient im) {
        this.changeEventRepository = changeEventRepository;
        this.memberRepository = memberRepository;
        this.projection = projection;
        this.ownedGroupService = ownedGroupService;
        this.im = im;
    }

    public record RepairItem(String groupId, String reason, boolean dismissed, long localMemberCount) {}

    public record RepairReport(boolean applied, int candidateCount, int repairedCount, List<RepairItem> items) {}

    /** 有 group_dismissed 事件但 profile.dismissed=false 的群。 */
    public List<String> findDismissEventNotAppliedGroupIds() {
        return changeEventRepository.findGroupIdsWithActionButProfileNotDismissed(
            GroupRealtimePublisher.ACTION_GROUP_DISMISSED);
    }

    public RepairReport dryRunByGroupIds(List<String> groupIds) {
        return repair(groupIds, false, false);
    }

    @Transactional
    public RepairReport applyByGroupIds(List<String> groupIds) {
        return repair(groupIds, true, false);
    }

    /** 扫描事件未落 dismissed 的群；可选再对候选做 IM 10010 确认（verifyImGone=true）。 */
    public RepairReport dryRunDismissEventMismatch(boolean verifyImGone) {
        return repair(findDismissEventNotAppliedGroupIds(), false, verifyImGone);
    }

    @Transactional
    public RepairReport applyDismissEventMismatch(boolean verifyImGone) {
        return repair(findDismissEventNotAppliedGroupIds(), true, verifyImGone);
    }

    private RepairReport repair(List<String> groupIds, boolean apply, boolean verifyImGone) {
        List<String> ids = normalizeIds(groupIds);
        List<RepairItem> items = new ArrayList<>();
        int repaired = 0;
        Set<String> imGone = verifyImGone ? resolveImGone(ids) : null;
        for (String groupId : ids) {
            if (verifyImGone && imGone != null && !imGone.contains(groupId)) {
                continue;
            }
            boolean dismissed = projection.isLocallyDismissed(groupId);
            long members = memberRepository.countByGroupId(groupId);
            String reason = dismissed && members == 0
                ? "ALREADY_CLEAN"
                : (verifyImGone ? "IM_GONE_OR_EVENT" : "DISMISS_EVENT_NOT_APPLIED");
            if ("ALREADY_CLEAN".equals(reason)) {
                items.add(new RepairItem(groupId, reason, true, members));
                continue;
            }
            items.add(new RepairItem(groupId, reason, dismissed, members));
            if (apply) {
                projection.onGroupDismissed(groupId);
                ownedGroupService.recordDestroyed(groupId);
                repaired++;
                log.info("dismiss projection repaired groupId={} reason={}", groupId, reason);
            }
        }
        return new RepairReport(apply, items.size(), repaired, items);
    }

    private Set<String> resolveImGone(List<String> groupIds) {
        Set<String> gone = new LinkedHashSet<>();
        for (int i = 0; i < groupIds.size(); i += IM_INFO_BATCH) {
            List<String> batch = groupIds.subList(i, Math.min(i + IM_INFO_BATCH, groupIds.size()));
            Map<String, GroupAdminInfo> infos = im.fetchGroupAdminInfoMap(batch);
            for (String gid : batch) {
                if (!infos.containsKey(gid)) {
                    gone.add(gid);
                }
            }
        }
        return gone;
    }

    private static List<String> normalizeIds(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : groupIds) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }
}
