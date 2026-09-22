package com.chat99.server.robot;

import com.chat99.server.robot.agent.AgentPlayerContext;
import com.chat99.server.robot.agent.AgentPlayerRepository;
import com.chat99.server.robot.agent.AgentPlayerRow;
import com.chat99.server.robot.agent.AgentRebateMath;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class RobotRebateTaskService {

    private static final Logger log = LoggerFactory.getLogger(RobotRebateTaskService.class);
    private static final int DEFAULT_PULL_LIMIT = 20;

    private final AgentPlayerRepository playerRepository;
    private final RobotRuntimeStateRepository runtimeStateRepository;
    private final RobotStateService robotStateService;
    private final RobotRebateRequestRepository rebateRequestRepository;
    private final TransactionTemplate robotTransactionTemplate;

    public RobotRebateTaskService(
            AgentPlayerRepository playerRepository,
            RobotRuntimeStateRepository runtimeStateRepository,
            RobotStateService robotStateService,
            RobotRebateRequestRepository rebateRequestRepository,
            @Qualifier("robotTransactionTemplate") TransactionTemplate robotTransactionTemplate) {
        this.playerRepository = playerRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.robotStateService = robotStateService;
        this.rebateRequestRepository = rebateRequestRepository;
        this.robotTransactionTemplate = robotTransactionTemplate;
    }

    public ApplyResponse applyPlayer(String machineCode, String userId) {
        log.info("rebate apply start type=PLAYER machineCode={} userId={}", machineCode, userId);
        AgentPlayerContext context = requirePlayerContext(machineCode, userId);
        robotStateService.requireReady(context.playerGroupId());
        AgentPlayerRow player = context.player();
        validateRealPlayer(player);
        return apply(context, player, "PLAYER");
    }

    public ApplyResponse applyAgent(String machineCode, String userId) {
        log.info("rebate apply start type=AGENT machineCode={} userId={}", machineCode, userId);
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        robotStateService.requireReady(context.playerGroupId());
        AgentPlayerRow player = context.player();
        validateRealPlayer(player);
        return apply(context, player, "AGENT");
    }

    public ApplyStatusResponse getApplyStatus(String machineCode, String userId) {
        AgentPlayerContext context = requirePlayerContext(machineCode, userId);
        String generation = runtimeStateRepository.requireDatabaseGeneration(context.playerGroupId());
        RobotRebateRequestState state = rebateRequestRepository
            .findByWxid(context.playerGroupId(), generation, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND"));
        if (state.requestId() == null || "NONE".equals(state.status())) {
            return ApplyStatusResponse.none(userId);
        }
        return toApplyStatus(state);
    }

    public PullResponse pull(PullRequest request) {
        int limit = request.limit() == null || request.limit() <= 0 ? DEFAULT_PULL_LIMIT : Math.min(request.limit(), 100);
        String machineCode = blankToNull(request.robotId());
        if (machineCode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MACHINE_CODE_REQUIRED");
        }
        String databaseGeneration = blankToNull(request.databaseGeneration());
        if (databaseGeneration == null) {
            databaseGeneration = runtimeStateRepository.requireDatabaseGeneration(machineCode);
        }
        robotStateService.requireReady(machineCode, databaseGeneration);
        List<RobotRebateRequestState> candidates =
            rebateRequestRepository.findPullable(machineCode, databaseGeneration, limit);

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (RobotRebateRequestState state : candidates) {
            if ("PENDING".equals(state.status())) {
                rebateRequestRepository.markProcessing(state.playerGroupId(), state.wxid(), state.requestId());
            }
            tasks.add(toPullTask(state, state.databaseGeneration()));
        }
        return new PullResponse(true, tasks);
    }

    public ResultResponse reportResult(String machineCode, ResultRequest request) {
        log.info(
            "rebate result start machineCode={} taskId={} settlementType={} applicantWxid={} success={} retryable={} resultCode={} resultMessage={}",
            machineCode,
            request.taskId(),
            request.settlementType(),
            request.applicantWxid(),
            request.success(),
            request.retryable(),
            request.resultCode(),
            request.resultMessage());
        ParsedTaskId parsed = parseTaskId(request.taskId());
        // taskId 内嵌 player_group_id（= machine_code）；必须与 Header 机器码一致
        String robotId = parsed.robotId();
        if (!machineCode.equals(robotId)) {
            log.warn(
                "rebate result rejected code=MACHINE_CODE_MISMATCH headerMachineCode={} taskRobotId={} taskId={}",
                machineCode,
                robotId,
                request.taskId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MACHINE_CODE_MISMATCH");
        }

        String currentGeneration = runtimeStateRepository.requireDatabaseGeneration(robotId);
        String callbackGeneration = request.databaseGeneration() != null && !request.databaseGeneration().isBlank()
            ? request.databaseGeneration().trim()
            : parsed.databaseGeneration();
        if (!currentGeneration.equals(callbackGeneration)) {
            log.warn(
                "rebate result rejected code=STALE_DATABASE_GENERATION machineCode={} currentGen={} callbackGen={} taskId={}",
                machineCode,
                currentGeneration,
                callbackGeneration,
                request.taskId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_DATABASE_GENERATION");
        }

        RobotRebateRequestState state = rebateRequestRepository.findByRequestId(robotId, parsed.requestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REBATE_TASK_NOT_FOUND"));
        validateResultRequest(request, state, parsed);

        boolean updated = Boolean.TRUE.equals(request.success())
            ? rebateRequestRepository.markSuccess(
                robotId,
                state.requestId(),
                state.leaseToken(),
                request.consumedFlow(),
                request.rebateAmount(),
                request.resultCode(),
                request.resultMessage())
            : rebateRequestRepository.markFailed(
                robotId,
                state.requestId(),
                state.leaseToken(),
                Boolean.TRUE.equals(request.retryable()),
                request.resultCode(),
                request.resultMessage());
        if (!updated) {
            if ("SUCCESS".equals(state.status()) || "FAILED".equals(state.status())) {
                log.info(
                    "rebate result already finalized machineCode={} requestId={} status={} taskId={}",
                    machineCode,
                    state.requestId(),
                    state.status(),
                    request.taskId());
                return new ResultResponse(true, "already finalized");
            }
            log.warn(
                "rebate result rejected code=REBATE_TASK_NOT_PROCESSING machineCode={} requestId={} status={} taskId={}",
                machineCode,
                state.requestId(),
                state.status(),
                request.taskId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REBATE_TASK_NOT_PROCESSING");
        }
        if (Boolean.TRUE.equals(request.success())) {
            log.info(
                "rebate result accepted machineCode={} requestId={} settlementType={} wxid={} consumedFlow={} rebateAmount={} resultCode={}",
                machineCode,
                state.requestId(),
                state.settlementType(),
                state.wxid(),
                request.consumedFlow(),
                request.rebateAmount(),
                request.resultCode());
            return new ResultResponse(true, "accepted");
        }
        log.warn(
            "rebate result failure recorded machineCode={} requestId={} settlementType={} wxid={} retryable={} resultCode={} resultMessage={}",
            machineCode,
            state.requestId(),
            state.settlementType(),
            state.wxid(),
            request.retryable(),
            request.resultCode(),
            request.resultMessage());
        return new ResultResponse(true, "failure recorded");
    }

    private ApplyResponse apply(AgentPlayerContext context, AgentPlayerRow player, String settlementType) {
        String playerGroupId = context.playerGroupId();
        String databaseGeneration = runtimeStateRepository.requireDatabaseGeneration(playerGroupId);
        var existing = rebateRequestRepository.findActiveByWxid(playerGroupId, databaseGeneration, player.wxid());
        if (existing.isPresent()) {
            log.info(
                "rebate apply existing type={} machineCode={} userId={} requestId={} status={} remainingFlow={} rebateRate={} agentPendingRebate={}",
                settlementType,
                playerGroupId,
                player.wxid(),
                existing.get().requestId(),
                existing.get().status(),
                player.remainingFlow(),
                player.rebateRate(),
                player.agentPendingRebate());
            return toApplyResponse(existing.get(), playerGroupId, true);
        }

        if ("PLAYER".equals(settlementType)) {
            validatePlayerSettlement(player);
        }

        String requestId = "RR" + UUID.randomUUID().toString().replace("-", "");
        String leaseToken = "RLT" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        BigDecimal flowToConsume = BigDecimal.ZERO;
        BigDecimal rebateAmount = BigDecimal.ZERO;
        BigDecimal expectedBalance = null;
        BigDecimal expectedTotalFlow = null;
        BigDecimal expectedUsedFlow = null;

        if ("PLAYER".equals(settlementType)) {
            flowToConsume = AgentRebateMath.money(player.remainingFlow());
            rebateAmount = AgentRebateMath.pendingRebate(player.remainingFlow(), player.rebateRate());
            expectedBalance = AgentRebateMath.money(player.balance().add(rebateAmount));
            expectedTotalFlow = AgentRebateMath.money(player.totalFlow());
            expectedUsedFlow = AgentRebateMath.money(player.usedFlow().add(flowToConsume));
        }

        rebateRequestRepository.createRequest(new RobotRebateRequestRepository.RobotRebateRequestDraft(
            playerGroupId,
            player.wxid(),
            requestId,
            leaseToken,
            settlementType,
            now,
            flowToConsume,
            rebateAmount,
            expectedBalance,
            expectedTotalFlow,
            expectedUsedFlow,
            databaseGeneration));

        RobotRebateRequestState created = rebateRequestRepository.findByRequestId(playerGroupId, requestId)
            .orElseThrow(() -> new IllegalStateException("rebate request not found after create"));
        log.info(
            "rebate apply created type={} machineCode={} userId={} requestId={} status={} remainingFlow={} rebateRate={} agentPendingRebate={} flowToConsume={} rebateAmount={}",
            settlementType,
            playerGroupId,
            player.wxid(),
            created.requestId(),
            created.status(),
            player.remainingFlow(),
            player.rebateRate(),
            player.agentPendingRebate(),
            flowToConsume,
            rebateAmount);
        return toApplyResponse(created, playerGroupId, false);
    }

    private void validatePlayerSettlement(AgentPlayerRow player) {
        BigDecimal remainingFlow = AgentRebateMath.money(player.remainingFlow());
        BigDecimal rebateAmount = AgentRebateMath.pendingRebate(player.remainingFlow(), player.rebateRate());
        if (remainingFlow.signum() <= 0 || rebateAmount.signum() <= 0) {
            log.warn(
                "rebate apply rejected code=NOTHING_TO_SETTLE userId={} remainingFlow={} rebateRate={} pendingRebate={}",
                player.wxid(),
                remainingFlow,
                player.rebateRate(),
                rebateAmount);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NOTHING_TO_SETTLE");
        }
    }

    private static void validateRealPlayer(AgentPlayerRow player) {
        if (!player.realPlayer()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MANUAL_PLAYER_NOT_ALLOWED");
        }
    }

    private static void validateResultRequest(ResultRequest request, RobotRebateRequestState state, ParsedTaskId parsed) {
        if (!state.requestId().equals(parsed.requestId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TASK_ID_MISMATCH");
        }
        if (request.leaseToken() == null || !request.leaseToken().equals(state.leaseToken())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LEASE_TOKEN_MISMATCH");
        }
        if (request.applicantWxid() != null && !request.applicantWxid().equals(state.wxid())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "APPLICANT_MISMATCH");
        }
        if (request.settlementType() != null && !request.settlementType().equals(state.settlementType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SETTLEMENT_TYPE_MISMATCH");
        }
        if (!parsed.databaseGeneration().equals(state.databaseGeneration())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_DATABASE_GENERATION");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, Object> toPullTask(RobotRebateRequestState state, String databaseGeneration) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", buildTaskId(state));
        task.put("leaseToken", state.leaseToken());
        task.put("databaseGeneration", databaseGeneration);
        task.put("applicantWxid", state.wxid());
        task.put("applicantNo", state.playerNo());
        task.put("settlementType", state.settlementType());
        if ("PLAYER".equals(state.settlementType())) {
            task.put("flowToConsume", numeric(state.flowToConsume()));
            task.put("rebateAmount", numeric(state.rebateAmount()));
            task.put("expectedBalance", numeric(state.expectedBalance()));
            task.put("expectedTotalFlow", numeric(state.expectedTotalFlow()));
            task.put("expectedUsedFlow", numeric(state.expectedUsedFlow()));
        } else {
            task.put("flowToConsume", 0);
            task.put("rebateAmount", 0);
        }
        return task;
    }

    private static ApplyResponse toApplyResponse(
            RobotRebateRequestState state, String playerGroupId, boolean existing) {
        return new ApplyResponse(
            state.wxid(),
            state.playerNo(),
            state.settlementType(),
            state.requestId(),
            buildTaskId(state),
            state.leaseToken(),
            state.databaseGeneration(),
            state.status(),
            numeric(state.flowToConsume()),
            numeric(state.rebateAmount()),
            existing);
    }

    private static ApplyStatusResponse toApplyStatus(RobotRebateRequestState state) {
        return new ApplyStatusResponse(
            state.wxid(),
            state.requestId(),
            buildTaskId(state),
            state.settlementType(),
            state.status(),
            state.leaseToken(),
            state.databaseGeneration(),
            state.requestedAt(),
            numeric(state.flowToConsume()),
            numeric(state.rebateAmount()),
            numeric(state.resultFlow()),
            numeric(state.resultAmount()),
            state.resultCode(),
            state.resultMessage(),
            state.retryable());
    }

    public static String buildTaskId(RobotRebateRequestState state) {
        return String.join(":",
            state.playerGroupId(),
            state.databaseGeneration(),
            state.wxid(),
            state.settlementType(),
            state.requestId());
    }

    private static ParsedTaskId parseTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TASK_ID_REQUIRED");
        }
        String[] parts = taskId.split(":", 5);
        if (parts.length != 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TASK_ID");
        }
        return new ParsedTaskId(parts[0], parts[1], parts[2], parts[3], parts[4]);
    }

    private static Double numeric(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private AgentPlayerContext requirePlayerContext(String machineCode, String userId) {
        return playerRepository.findPlayer(machineCode, userId)
            .map(player -> new AgentPlayerContext(machineCode, player))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND"));
    }

    private AgentPlayerContext requireAgentContext(String machineCode, String userId) {
        AgentPlayerContext context = requirePlayerContext(machineCode, userId);
        if (!AgentRebateMath.isAgentByRebateRate(context.player().rebateRate())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_AGENT");
        }
        return context;
    }

    public record PullRequest(String robotId, String databaseGeneration, Integer limit) {}

    public record PullResponse(boolean success, List<Map<String, Object>> data) {}

    public record ResultRequest(
        String taskId,
        String leaseToken,
        String robotId,
        String databaseGeneration,
        String settlementType,
        String applicantWxid,
        Boolean success,
        Boolean retryable,
        String resultCode,
        String resultMessage,
        BigDecimal consumedFlow,
        BigDecimal rebateAmount) {}

    public record ResultResponse(boolean success, String message) {}

    public record ApplyResponse(
        String userId,
        String playerNo,
        String settlementType,
        String requestId,
        String taskId,
        String leaseToken,
        String databaseGeneration,
        String status,
        Double flowToConsume,
        Double rebateAmount,
        boolean existing) {}

    public record ApplyStatusResponse(
        String userId,
        String requestId,
        String taskId,
        String settlementType,
        String status,
        String leaseToken,
        String databaseGeneration,
        LocalDateTime requestedAt,
        Double flowToConsume,
        Double rebateAmount,
        Double resultFlow,
        Double resultAmount,
        String resultCode,
        String resultMessage,
        Boolean retryable) {

        static ApplyStatusResponse none(String userId) {
            return new ApplyStatusResponse(
                userId, null, null, null, "NONE", null, null, null,
                null, null, null, null, null, null, null);
        }
    }

    private record ParsedTaskId(
        String robotId,
        String databaseGeneration,
        String applicantWxid,
        String settlementType,
        String requestId) {}
}
