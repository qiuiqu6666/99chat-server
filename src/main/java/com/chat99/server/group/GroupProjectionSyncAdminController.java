package com.chat99.server.group;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/group-projection")
public class GroupProjectionSyncAdminController {

    private final AdminGuard guard;
    private final GroupProjectionSyncService syncService;
    private final GroupDismissProjectionRepairService repairService;
    private final GroupFalseDismissRestoreService falseDismissRestoreService;

    public GroupProjectionSyncAdminController(AdminGuard guard,
                                              GroupProjectionSyncService syncService,
                                              GroupDismissProjectionRepairService repairService,
                                              GroupFalseDismissRestoreService falseDismissRestoreService) {
        this.guard = guard;
        this.syncService = syncService;
        this.repairService = repairService;
        this.falseDismissRestoreService = falseDismissRestoreService;
    }

    public record SyncBody(
        Integer maxGroups,
        Boolean syncUsers,
        Integer maxUsers,
        Set<String> groupTypes) {}

    @PostMapping("/sync")
    public Map<String, Object> start(@RequestBody(required = false) SyncBody body, HttpServletRequest http) {
        guard.check(http);
        GroupProjectionSyncService.SyncRequest request = toRequest(body);
        if (!syncService.startAsync(request)) {
            return Map.of("ok", false, "error", "ALREADY_RUNNING");
        }
        return Map.of("ok", true, "started", true, "request", request);
    }

    @PostMapping("/sync/run")
    public Map<String, Object> runSync(@RequestBody(required = false) SyncBody body, HttpServletRequest http) {
        guard.check(http);
        GroupProjectionSyncService.SyncSnapshot snapshot = syncService.runNow(toRequest(body));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("snapshot", snapshot);
        return out;
    }

    @PostMapping("/sync/group/{groupId}")
    public Map<String, Object> syncOne(@PathVariable String groupId, HttpServletRequest http) {
        guard.check(http);
        GroupProjectionService.GroupFullSyncResult result = syncService.syncOneGroup(groupId);
        return Map.of("ok", true, "result", result);
    }

    @GetMapping("/sync/status")
    public Map<String, Object> status(HttpServletRequest http) {
        guard.check(http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", syncService.isRunning());
        out.put("last", syncService.lastSnapshot());
        return out;
    }

    public record RepairBody(List<String> groupIds, Boolean verifyImGone) {}

    public record FalseDismissBody(List<String> groupIds, Boolean scanAllDismissed) {}

    /** dryRun：有 dismiss 事件但未标 dismissed 的投影。 */
    @PostMapping("/repair/dismiss/dry-run")
    public Map<String, Object> repairDismissDryRun(
        @RequestBody(required = false) RepairBody body, HttpServletRequest http) {
        guard.check(http);
        boolean verifyIm = body != null && Boolean.TRUE.equals(body.verifyImGone());
        GroupDismissProjectionRepairService.RepairReport report;
        if (body != null && body.groupIds() != null && !body.groupIds().isEmpty()) {
            report = repairService.dryRunByGroupIds(body.groupIds());
        } else {
            report = repairService.dryRunDismissEventMismatch(verifyIm);
        }
        return Map.of("ok", true, "report", report);
    }

    /** apply：收敛脏解散投影。默认扫事件未落库；也可传 groupIds。 */
    @PostMapping("/repair/dismiss/apply")
    public Map<String, Object> repairDismissApply(
        @RequestBody(required = false) RepairBody body, HttpServletRequest http) {
        guard.check(http);
        boolean verifyIm = body != null && Boolean.TRUE.equals(body.verifyImGone());
        GroupDismissProjectionRepairService.RepairReport report;
        if (body != null && body.groupIds() != null && !body.groupIds().isEmpty()) {
            report = repairService.applyByGroupIds(body.groupIds());
        } else {
            report = repairService.applyDismissEventMismatch(verifyIm);
        }
        return Map.of("ok", true, "report", report);
    }

    /** dry-run：本地 dismissed 但 IM 仍存活的假解散。 */
    @PostMapping("/repair/false-dismiss/dry-run")
    public Map<String, Object> falseDismissDryRun(
        @RequestBody(required = false) FalseDismissBody body, HttpServletRequest http) {
        guard.check(http);
        GroupFalseDismissRestoreService.RestoreReport report = resolveFalseDismiss(body, false);
        return Map.of("ok", true, "report", report);
    }

    /** apply：恢复假解散投影（清 dismissed + 从 IM 回填成员）。 */
    @PostMapping("/repair/false-dismiss/apply")
    public Map<String, Object> falseDismissApply(
        @RequestBody(required = false) FalseDismissBody body, HttpServletRequest http) {
        guard.check(http);
        GroupFalseDismissRestoreService.RestoreReport report = resolveFalseDismiss(body, true);
        return Map.of("ok", true, "report", report);
    }

    private GroupFalseDismissRestoreService.RestoreReport resolveFalseDismiss(
        FalseDismissBody body, boolean apply) {
        boolean scanAll = body != null && Boolean.TRUE.equals(body.scanAllDismissed());
        if (body != null && body.groupIds() != null && !body.groupIds().isEmpty()) {
            return apply
                ? falseDismissRestoreService.applyByGroupIds(body.groupIds())
                : falseDismissRestoreService.dryRunByGroupIds(body.groupIds());
        }
        if (scanAll) {
            return apply
                ? falseDismissRestoreService.applyAllLocallyDismissedImAlive()
                : falseDismissRestoreService.dryRunAllLocallyDismissed();
        }
        return new GroupFalseDismissRestoreService.RestoreReport(apply, 0, 0, List.of());
    }

    private static GroupProjectionSyncService.SyncRequest toRequest(SyncBody body) {
        if (body == null) {
            return GroupProjectionSyncService.SyncRequest.defaults();
        }
        int maxGroups = body.maxGroups() == null ? 0 : body.maxGroups();
        boolean syncUsers = Boolean.TRUE.equals(body.syncUsers());
        int maxUsers = body.maxUsers() == null ? 0 : body.maxUsers();
        Set<String> types = body.groupTypes();
        if (types == null || types.isEmpty()) {
            types = GroupProjectionSyncService.SyncRequest.defaults().groupTypes();
        }
        return new GroupProjectionSyncService.SyncRequest(maxGroups, syncUsers, maxUsers, types);
    }
}
