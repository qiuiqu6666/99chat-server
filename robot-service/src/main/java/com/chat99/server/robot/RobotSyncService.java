package com.chat99.server.robot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class RobotSyncService {

    private static final Logger log = LoggerFactory.getLogger(RobotSyncService.class);

    private final RobotSyncEventRepository eventRepository;
    private final RobotPlayerSnapshotRepository snapshotRepository;
    private final RobotPlayerDailySummaryRepository dailySummaryRepository;
    private final RobotPlayerUpdownRecordRepository updownRecordRepository;
    private final RobotRuntimeStateRepository runtimeStateRepository;
    private final RobotRebateRequestRepository rebateRequestRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate robotTransactionTemplate;

    public RobotSyncService(RobotSyncEventRepository eventRepository,
                            RobotPlayerSnapshotRepository snapshotRepository,
                            RobotPlayerDailySummaryRepository dailySummaryRepository,
                            RobotPlayerUpdownRecordRepository updownRecordRepository,
                            RobotRuntimeStateRepository runtimeStateRepository,
                            RobotRebateRequestRepository rebateRequestRepository,
                            ObjectMapper objectMapper,
                            @Qualifier("robotTransactionTemplate") TransactionTemplate robotTransactionTemplate) {
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.dailySummaryRepository = dailySummaryRepository;
        this.updownRecordRepository = updownRecordRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.rebateRequestRepository = rebateRequestRepository;
        this.objectMapper = objectMapper;
        this.robotTransactionTemplate = robotTransactionTemplate;
    }

    public RobotSyncResult process(RobotSyncRequest request) {
        return robotTransactionTemplate.execute(status -> {
            RobotSyncResult result = doProcess(request, false);
            if (!result.success()) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    /**
     * 整批一个事务；任一条业务失败则回滚并抛错（客户端整批重试）。
     * 重复 eventId / updown 唯一冲突视为成功（幂等）。
     */
    public RobotSyncBatchResult processBatch(RobotSyncBatchRequest batch, String machineCodeMasked) {
        long started = System.nanoTime();
        try {
            RobotSyncBatchResult result = robotTransactionTemplate.execute(status -> {
                int accepted = 0;
                int duplicates = 0;
                for (RobotSyncRequest event : batch.getEvents()) {
                    rejectControlEventInBatch(event);
                    RobotSyncResult one = doProcess(event, true);
                    if (!one.success()) {
                        status.setRollbackOnly();
                        throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            one.message() == null ? "batch event rejected" : one.message());
                    }
                    accepted++;
                    if (one.duplicated()) {
                        duplicates++;
                    }
                }
                return new RobotSyncBatchResult(batch.getBatchId(), accepted, duplicates);
            });
            long tookMs = (System.nanoTime() - started) / 1_000_000L;
            log.info(
                "robot sync batch ok machine={} batchId={} lane={} eventCount={} accepted={} duplicates={} tookMs={}",
                machineCodeMasked,
                batch.getBatchId(),
                batch.getLane(),
                batch.getEventCount(),
                result.accepted(),
                result.duplicates(),
                tookMs);
            return result;
        } catch (RuntimeException e) {
            long tookMs = (System.nanoTime() - started) / 1_000_000L;
            log.warn(
                "robot sync batch failed machine={} batchId={} lane={} eventCount={} tookMs={} reason={}",
                machineCodeMasked,
                batch.getBatchId(),
                batch.getLane(),
                batch.getEventCount(),
                tookMs,
                e.toString());
            throw e;
        }
    }

    private static void rejectControlEventInBatch(RobotSyncRequest event) {
        String type = event.getEventType();
        if (RobotSyncSupport.EVENT_DATABASE_INITIALIZED.equals(type)
            || RobotSyncSupport.EVENT_FULL_SNAPSHOT_COMPLETED.equals(type)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "control events must use single-event protocol");
        }
    }

    RobotSyncResult doProcess(RobotSyncRequest request, boolean batchMode) {
        RobotSyncSupport.validateEventType(request.getEventType());

        if (eventRepository.existsByEventId(request.getEventId())) {
            return RobotSyncResult.duplicate();
        }

        String requestBodyJson = serializeRequest(request);
        try {
            eventRepository.insertReceived(request, requestBodyJson);
        } catch (RobotSyncDuplicateEventException e) {
            return RobotSyncResult.duplicate();
        }

        RobotSyncResult result = switch (request.getEventType()) {
            case RobotSyncSupport.EVENT_SNAPSHOT_UPSERT -> processSnapshot(request, batchMode);
            case RobotSyncSupport.EVENT_DAILY_SUMMARY -> processDailySummary(request, batchMode);
            case RobotSyncSupport.EVENT_UPDOWN_RECORDED -> processUpdownRecorded(request, batchMode);
            case RobotSyncSupport.EVENT_DATABASE_INITIALIZED -> {
                processDatabaseInitialized(request);
                yield RobotSyncResult.accepted(0);
            }
            case RobotSyncSupport.EVENT_FULL_SNAPSHOT_COMPLETED -> {
                processFullSnapshotCompleted(request);
                yield RobotSyncResult.accepted(0);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported eventType");
        };

        if (result.success()) {
            eventRepository.markProcessed(request.getEventId());
        }
        return result;
    }

    private void processDatabaseInitialized(RobotSyncRequest request) {
        RobotSyncSupport.validateControlEvent(request);
        if (!RobotSyncSupport.REASON_DATABASE_RESET.equals(request.getSyncReason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid syncReason");
        }

        String robotId = RobotSyncSupport.resolveRobotId(request);
        String generation = request.getDatabaseGeneration().trim();

        runtimeStateRepository.markSyncing(robotId, generation, request.getBusinessTimestamp());
        int invalidatedSnapshots = snapshotRepository.invalidateOldGenerations(robotId, generation);
        int invalidatedRequests = rebateRequestRepository.invalidateOldGenerationRequests(robotId, generation);

        log.info(
            "robot database initialized robotId={} generation={} invalidatedSnapshots={} invalidatedRequests={}",
            robotId, generation, invalidatedSnapshots, invalidatedRequests);
    }

    private void processFullSnapshotCompleted(RobotSyncRequest request) {
        RobotSyncSupport.validateControlEvent(request);
        if (!RobotSyncSupport.REASON_FULL_REBUILD_COMPLETED.equals(request.getSyncReason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid syncReason");
        }

        String robotId = RobotSyncSupport.resolveRobotId(request);
        String generation = request.getDatabaseGeneration().trim();

        RobotRuntimeState state = runtimeStateRepository.findByRobotId(robotId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "robot runtime state not found"));

        if (!generation.equals(state.databaseGeneration())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "databaseGeneration mismatch");
        }
        if (!state.syncing()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "robot is not syncing");
        }

        int playerCount = readPlayerCount(request.getData());
        boolean updated = runtimeStateRepository.markReady(
            robotId, generation, playerCount, request.getBusinessTimestamp());
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "robot is not syncing");
        }

        log.info("robot full snapshot completed robotId={} generation={} playerCount={}",
            robotId, generation, playerCount);
    }

    private RobotSyncResult processSnapshot(RobotSyncRequest request, boolean batchMode) {
        PlayerSnapshotData data = parseData(request, PlayerSnapshotData.class);
        if (data.getWxid() == null || data.getWxid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.wxid is required");
        }
        validateEntityMatchesWxid(request, data.getWxid());

        String generation = resolveSnapshotGeneration(request);
        String robotId = RobotSyncSupport.resolveRobotId(request);
        var current = snapshotRepository.findVersion(
            request.getPlayerGroupId(), generation, data.getWxid());

        if (!batchMode) {
            log.info(
                "robot snapshot agentPending robotId={} playerGroupId={} databaseGeneration={} wxid={} "
                    + "agentPendingFlow={} agentPendingRebate={} sourceUpdatedAt={} active={}",
                robotId,
                request.getPlayerGroupId(),
                generation,
                data.getWxid(),
                formatPending(data.getAgentPendingFlow()),
                formatPending(data.getAgentPendingRebate()),
                request.getSourceUpdatedAt(),
                data.getActive());
        }

        if (current.isEmpty()) {
            int inserted = snapshotRepository.insert(request, data, generation);
            if (!batchMode) {
                log.info(
                    "robot snapshot inserted wxid={} updatedCount={} agentPendingFlow={} agentPendingRebate={} active={}",
                    data.getWxid(), inserted, formatPending(data.getAgentPendingFlow()),
                    formatPending(data.getAgentPendingRebate()), data.getActive());
            }
            return RobotSyncResult.accepted(inserted);
        }

        if (isIncomingSnapshotNewer(request, current.get())) {
            int updated = snapshotRepository.update(request, data, generation);
            if (!batchMode) {
                log.info(
                    "robot snapshot updated wxid={} updatedCount={} agentPendingFlow={} agentPendingRebate={} active={}",
                    data.getWxid(), updated, formatPending(data.getAgentPendingFlow()),
                    formatPending(data.getAgentPendingRebate()), data.getActive());
            }
            if (updated <= 0) {
                return RobotSyncResult.notUpdated("玩家不存在或更新0条");
            }
            return RobotSyncResult.accepted(updated);
        }

        if (!batchMode) {
            log.info("skip stale snapshot eventId={} playerGroupId={} wxid={} incoming={} current={} lastEventId={}",
                request.getEventId(),
                request.getPlayerGroupId(),
                data.getWxid(),
                request.getSourceUpdatedAt(),
                current.get().sourceUpdatedAt(),
                current.get().lastEventId());
        }
        return RobotSyncResult.accepted(0);
    }

    /** sourceUpdatedAt 优先；相等时用 eventId 字典序更大者赢（防重试乱序）。 */
    static boolean isIncomingSnapshotNewer(RobotSyncRequest incoming, RobotPlayerSnapshotRepository.SnapshotVersion stored) {
        long inTs = incoming.getSourceUpdatedAt() == null ? 0L : incoming.getSourceUpdatedAt();
        if (inTs > stored.sourceUpdatedAt()) {
            return true;
        }
        if (inTs < stored.sourceUpdatedAt()) {
            return false;
        }
        String inEvent = incoming.getEventId() == null ? "" : incoming.getEventId();
        String curEvent = stored.lastEventId() == null ? "" : stored.lastEventId();
        return inEvent.compareTo(curEvent) > 0;
    }

    private RobotSyncResult processDailySummary(RobotSyncRequest request, boolean batchMode) {
        PlayerDailySummaryData data = parseData(request, PlayerDailySummaryData.class);
        if (data.getWxid() == null || data.getWxid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.wxid is required");
        }
        validateEntityMatchesWxid(request, data.getWxid());

        var businessDate = RobotSyncSupport.resolveBusinessDate(
            request.getBusinessTimestamp(), request.getBusinessTimezone());

        if (!batchMode) {
            log.info(
                "robot dailySummary agentPending robotId={} playerGroupId={} wxid={} businessDate={} "
                    + "agentPendingFlow={} agentPendingRebate={}",
                RobotSyncSupport.resolveRobotId(request),
                request.getPlayerGroupId(),
                data.getWxid(),
                businessDate,
                formatPending(data.getAgentPendingFlow()),
                formatPending(data.getAgentPendingRebate()));
        }

        try {
            int inserted = dailySummaryRepository.insert(request, data, businessDate);
            if (!batchMode) {
                log.info(
                    "robot dailySummary inserted wxid={} updatedCount={} agentPendingFlow={} agentPendingRebate={}",
                    data.getWxid(), inserted, formatPending(data.getAgentPendingFlow()),
                    formatPending(data.getAgentPendingRebate()));
            }
            if (inserted <= 0) {
                return RobotSyncResult.notUpdated("玩家不存在或更新0条");
            }
            return RobotSyncResult.accepted(inserted);
        } catch (DuplicateKeyException e) {
            // event_id 唯一：整批重试安全
            return RobotSyncResult.duplicate();
        }
    }

    private RobotSyncResult processUpdownRecorded(RobotSyncRequest request, boolean batchMode) {
        PlayerUpdownRecordData data = parseData(request, PlayerUpdownRecordData.class);
        if (data.getRecordId() == null || data.getRecordId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.recordId is required");
        }
        if (data.getWxid() == null || data.getWxid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.wxid is required");
        }
        if (!"UP".equals(data.getDirection()) && !"DOWN".equals(data.getDirection())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.direction must be UP or DOWN");
        }
        if (data.getAmount() == null
            || data.getBalanceDelta() == null
            || data.getBalanceAfter() == null
            || data.getTotalUpAfter() == null
            || data.getTotalDownAfter() == null
            || data.getApprovedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data amount fields are required");
        }
        if (data.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.amount must be > 0");
        }
        if ("UP".equals(data.getDirection()) && data.getBalanceDelta().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.balanceDelta must be > 0 when direction=UP");
        }
        if ("DOWN".equals(data.getDirection()) && data.getBalanceDelta().compareTo(BigDecimal.ZERO) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data.balanceDelta must be < 0 when direction=DOWN");
        }
        data.setApprovedAt(normalizeApprovedAtSeconds(data.getApprovedAt()));
        validateEntityMatchesWxid(request, data.getWxid());

        try {
            int inserted = updownRecordRepository.insert(request, data);
            if (!batchMode) {
                log.info(
                    "robot updown recorded eventId={} playerGroupId={} recordId={} wxid={} direction={} amount={} status={} approvalSource={}",
                    request.getEventId(),
                    request.getPlayerGroupId(),
                    data.getRecordId(),
                    data.getWxid(),
                    data.getDirection(),
                    data.getAmount().toPlainString(),
                    data.getStatus(),
                    data.getApprovalSource());
            }
            return RobotSyncResult.accepted(inserted);
        } catch (DuplicateKeyException e) {
            // eventId / recordId 唯一：响应丢失重试必须 2xx
            return RobotSyncResult.duplicate();
        }
    }

    /** Windows 可发秒或毫秒；库内统一存 epoch 秒。 */
    static long normalizeApprovedAtSeconds(long approvedAt) {
        if (approvedAt >= 1_000_000_000_000L) {
            return approvedAt / 1000L;
        }
        return approvedAt;
    }

    private static void validateEntityMatchesWxid(RobotSyncRequest request, String wxid) {
        if (request.getEntityId() != null
            && !request.getEntityId().isBlank()
            && !request.getEntityId().equals(wxid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId must match data.wxid");
        }
    }

    private static String formatPending(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private String resolveSnapshotGeneration(RobotSyncRequest request) {
        String robotId = RobotSyncSupport.resolveRobotId(request);
        if (request.getDatabaseGeneration() == null || request.getDatabaseGeneration().isBlank()) {
            return runtimeStateRepository.requireDatabaseGeneration(robotId);
        }

        String reported = request.getDatabaseGeneration().trim();
        var stateOpt = runtimeStateRepository.findByRobotId(robotId);
        if (stateOpt.isEmpty()) {
            runtimeStateRepository.upsertDatabaseGeneration(robotId, reported);
            return reported;
        }

        RobotRuntimeState state = stateOpt.get();
        if (state.syncing()) {
            if (!reported.equals(state.databaseGeneration())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "databaseGeneration mismatch");
            }
            return reported;
        }

        // READY：机器人若已换代但漏发控制事件，用快照代次自愈 runtime，避免代理接口读到旧代
        if (!reported.equals(state.databaseGeneration())) {
            runtimeStateRepository.upsertDatabaseGeneration(robotId, reported);
            int invalidatedSnapshots = snapshotRepository.invalidateOldGenerations(robotId, reported);
            int invalidatedRequests = rebateRequestRepository.invalidateOldGenerationRequests(robotId, reported);
            log.info(
                "healed robot runtime generation robotId={} from={} to={} invalidatedSnapshots={} invalidatedRequests={}",
                robotId, state.databaseGeneration(), reported, invalidatedSnapshots, invalidatedRequests);
        }
        return reported;
    }

    private static int readPlayerCount(JsonNode data) {
        if (data == null || data.isNull() || !data.has("playerCount") || data.get("playerCount").isNull()) {
            return 0;
        }
        JsonNode node = data.get("playerCount");
        int value;
        if (node.isNumber()) {
            value = node.intValue();
        } else {
            try {
                value = Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return Math.max(value, 0);
    }

    private <T> T parseData(RobotSyncRequest request, Class<T> type) {
        try {
            return objectMapper.treeToValue(request.getData(), type);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid data payload");
        }
    }

    private String serializeRequest(RobotSyncRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request body");
        }
    }
}
