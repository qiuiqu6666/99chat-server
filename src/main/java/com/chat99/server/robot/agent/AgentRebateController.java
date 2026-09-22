package com.chat99.server.robot.agent;

import com.chat99.server.robot.GroupTenantResolver;
import com.chat99.server.robot.MachineCodeSupport;
import com.chat99.server.robot.RobotRebateTaskService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class AgentRebateController {

    private final AgentRebateService rebateService;
    private final AgentReportExportService exportService;
    private final RobotRebateTaskService rebateTaskService;
    private final GroupTenantResolver groupTenantResolver;

    public AgentRebateController(
            AgentRebateService rebateService,
            AgentReportExportService exportService,
            RobotRebateTaskService rebateTaskService,
            GroupTenantResolver groupTenantResolver) {
        this.rebateService = rebateService;
        this.exportService = exportService;
        this.rebateTaskService = rebateTaskService;
        this.groupTenantResolver = groupTenantResolver;
    }

    @GetMapping("/me/agent/descendants/history")
    public AgentRebateService.AgentDescendantHistoryResponse descendantHistory(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String userId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getDescendantHistory(machineCode, requireUser(auth), startDate, endDate, userId);
    }

    @GetMapping("/me/agent/descendants")
    public AgentRebateService.AgentDescendantListResponse descendants(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @RequestParam(defaultValue = "all") String scope,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getDescendants(machineCode, requireUser(auth), scope);
    }

    @GetMapping("/me/agent/first-level-agents")
    public AgentRebateService.FirstLevelAgentGroupsResponse firstLevelAgents(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getFirstLevelAgentGroups(machineCode, requireUser(auth));
    }

    @GetMapping("/me/agent/descendants/{userId}")
    public AgentRebateService.AgentDescendantDetailResponse descendantDetail(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @PathVariable("userId") String targetUserId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getDescendantDetail(machineCode, requireUser(auth), targetUserId);
    }

    @PostMapping("/me/agent/rebate/apply")
    public RobotRebateTaskService.ApplyResponse applyAgent(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateTaskService.applyAgent(machineCode, requireUser(auth));
    }

    @GetMapping("/me/agent/rebate/apply/status")
    public RobotRebateTaskService.ApplyStatusResponse applyAgentStatus(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateTaskService.getApplyStatus(machineCode, requireUser(auth));
    }

    @GetMapping("/me/agent/player")
    public AgentRebateService.AgentPlayerProfileResponse playerProfile(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getPlayerProfile(machineCode, requireUser(auth));
    }

    @GetMapping("/me/agent/rebate/current")
    public AgentRebateService.AgentCurrentRebateResponse currentRebate(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getCurrentRebate(machineCode, requireUser(auth));
    }

    @GetMapping("/me/agent/rebate/history")
    public AgentRebateService.AgentHistoryRebateResponse historyRebate(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateService.getHistoryRebate(machineCode, requireUser(auth), startDate, endDate);
    }

    @PostMapping("/me/agent/rebate/history/export")
    public AgentReportExportService.ExportCreatedResponse exportHistory(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @RequestBody ExportHistoryRequest body,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return exportService.submitExport(machineCode, requireUser(auth), body.toServiceRequest());
    }

    @GetMapping("/me/agent/rebate/history/export/{taskId}")
    public AgentReportExportService.ExportTaskResponse exportTask(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @PathVariable("taskId") String taskId,
            Authentication auth) {
        groupTenantResolver.requireMachineCode(groupId);
        return exportService.getTask(requireUser(auth), taskId);
    }

    @GetMapping("/me/agent/rebate/history/export/{taskId}/download")
    public ResponseEntity<byte[]> downloadExport(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            @PathVariable("taskId") String taskId,
            Authentication auth) {
        groupTenantResolver.requireMachineCode(groupId);
        AgentReportExportService.DownloadFile file = exportService.download(requireUser(auth), taskId);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
            .contentType(MediaType.parseMediaType(file.contentType()))
            .body(file.data());
    }

    private static String requireUser(Authentication auth) {
        return (String) auth.getPrincipal();
    }

    public record ExportHistoryRequest(
        LocalDate startDate,
        LocalDate endDate,
        String fileType,
        Boolean includeDetail,
        Boolean includeAgentDetail,
        Boolean includePlayerDetail) {

        AgentReportExportService.ExportRequest toServiceRequest() {
            return new AgentReportExportService.ExportRequest(
                startDate, endDate, fileType, includeDetail, includeAgentDetail, includePlayerDetail);
        }
    }
}
