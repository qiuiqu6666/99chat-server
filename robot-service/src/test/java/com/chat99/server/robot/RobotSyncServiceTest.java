package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RobotSyncServiceTest {

    @Mock
    private RobotSyncEventRepository eventRepository;
    @Mock
    private RobotPlayerSnapshotRepository snapshotRepository;
    @Mock
    private RobotPlayerDailySummaryRepository dailySummaryRepository;
    @Mock
    private RobotPlayerUpdownRecordRepository updownRecordRepository;
    @Mock
    private RobotRuntimeStateRepository runtimeStateRepository;
    @Mock
    private RobotRebateRequestRepository rebateRequestRepository;

    private RobotSyncService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        org.springframework.transaction.PlatformTransactionManager tm =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override
                public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }

                @Override
                public void commit(org.springframework.transaction.TransactionStatus status) {
                }

                @Override
                public void rollback(org.springframework.transaction.TransactionStatus status) {
                }
            };
        var tx = new org.springframework.transaction.support.TransactionTemplate(tm);
        service = new RobotSyncService(
            eventRepository,
            snapshotRepository,
            dailySummaryRepository,
            updownRecordRepository,
            runtimeStateRepository,
            rebateRequestRepository,
            objectMapper,
            tx);
    }

    @Test
    void duplicateEventReturnsDuplicateWithoutBusinessUpdate() {
        var request = snapshotRequest("evt-dup", 200L, "100.00");
        when(eventRepository.existsByEventId("evt-dup")).thenReturn(true);

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isTrue();
        verify(snapshotRepository, never()).insert(any(), any(), anyString());
        verify(snapshotRepository, never()).update(any(), any(), anyString());
    }

    @Test
    void insertSnapshotWhenPlayerMissing() {
        var request = snapshotRequest("evt-new", 200L, "100.00");
        when(eventRepository.existsByEventId("evt-new")).thenReturn(false);
        when(runtimeStateRepository.requireDatabaseGeneration("group_001")).thenReturn("gen-1");
        when(snapshotRepository.findVersion("group_001", "gen-1", "wxid_001")).thenReturn(Optional.empty());
        when(snapshotRepository.insert(eq(request), any(PlayerSnapshotData.class), eq("gen-1"))).thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isFalse();
        assertThat(result.updatedCount()).isEqualTo(1);
        verify(snapshotRepository).insert(eq(request), any(PlayerSnapshotData.class), eq("gen-1"));
        verify(eventRepository).markProcessed("evt-new");
    }

    @Test
    void updateSnapshotWhenSourceUpdatedAtIsNewer() {
        var request = snapshotRequest("evt-update", 300L, "200.00");
        request.setDatabaseGeneration("gen-2");
        when(eventRepository.existsByEventId("evt-update")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("group_001")).thenReturn(Optional.of(
            new RobotRuntimeState("group_001", "gen-2", "READY", 0, null, null, null)));
        when(snapshotRepository.findVersion("group_001", "gen-2", "wxid_001")).thenReturn(Optional.of(new RobotPlayerSnapshotRepository.SnapshotVersion(200L, "evt-old")));
        when(snapshotRepository.update(eq(request), any(PlayerSnapshotData.class), eq("gen-2"))).thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isFalse();
        assertThat(result.updatedCount()).isEqualTo(1);
        verify(snapshotRepository).update(eq(request), any(PlayerSnapshotData.class), eq("gen-2"));
        verify(runtimeStateRepository, never()).upsertDatabaseGeneration(anyString(), anyString());
    }

    @Test
    void updateSnapshotReturnsFailureWhenZeroRows() {
        var request = snapshotRequest("evt-zero", 300L, "200.00");
        request.setDatabaseGeneration("gen-2");
        when(eventRepository.existsByEventId("evt-zero")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("group_001")).thenReturn(Optional.of(
            new RobotRuntimeState("group_001", "gen-2", "READY", 0, null, null, null)));
        when(snapshotRepository.findVersion("group_001", "gen-2", "wxid_001")).thenReturn(Optional.of(new RobotPlayerSnapshotRepository.SnapshotVersion(200L, "evt-old")));
        when(snapshotRepository.update(eq(request), any(PlayerSnapshotData.class), eq("gen-2"))).thenReturn(0);

        var result = service.doProcess(request, false);

        assertThat(result.success()).isFalse();
        assertThat(result.updatedCount()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("玩家不存在或更新0条");
        verify(eventRepository, never()).markProcessed("evt-zero");
    }

    @Test
    void snapshotWithNewGenerationHealsReadyRuntime() {
        var request = snapshotRequest("evt-heal", 300L, "200.00");
        request.setDatabaseGeneration("gen-2");
        when(eventRepository.existsByEventId("evt-heal")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("group_001")).thenReturn(Optional.of(
            new RobotRuntimeState("group_001", "gen-1", "READY", 0, null, null, null)));
        when(snapshotRepository.findVersion("group_001", "gen-2", "wxid_001")).thenReturn(Optional.empty());
        when(snapshotRepository.invalidateOldGenerations("group_001", "gen-2")).thenReturn(4);
        when(rebateRequestRepository.invalidateOldGenerationRequests("group_001", "gen-2")).thenReturn(0);
        when(snapshotRepository.insert(eq(request), any(PlayerSnapshotData.class), eq("gen-2"))).thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isFalse();
        verify(runtimeStateRepository).upsertDatabaseGeneration("group_001", "gen-2");
        verify(snapshotRepository).invalidateOldGenerations("group_001", "gen-2");
        verify(snapshotRepository).insert(eq(request), any(PlayerSnapshotData.class), eq("gen-2"));
    }

    @Test
    void snapshotRejectsMismatchedGenerationWhileSyncing() {
        var request = snapshotRequest("evt-sync-mismatch", 300L, "200.00");
        request.setDatabaseGeneration("gen-2");
        when(eventRepository.existsByEventId("evt-sync-mismatch")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("group_001")).thenReturn(Optional.of(
            new RobotRuntimeState("group_001", "gen-1", "SYNCING", 0, null, null, null)));

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class);
        verify(snapshotRepository, never()).insert(any(), any(), anyString());
        verify(runtimeStateRepository, never()).upsertDatabaseGeneration(anyString(), anyString());
    }

    @Test
    void skipSnapshotUpdateWhenSourceUpdatedAtIsOlder() {
        var request = snapshotRequest("evt-stale", 100L, "50.00");
        when(eventRepository.existsByEventId("evt-stale")).thenReturn(false);
        when(runtimeStateRepository.requireDatabaseGeneration("group_001")).thenReturn("gen-1");
        when(snapshotRepository.findVersion("group_001", "gen-1", "wxid_001")).thenReturn(Optional.of(new RobotPlayerSnapshotRepository.SnapshotVersion(200L, "evt-newer")));

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isFalse();
        verify(snapshotRepository, never()).update(any(), any(), anyString());
        verify(snapshotRepository, never()).insert(any(), any(), anyString());
        verify(eventRepository).markProcessed("evt-stale");
    }

    @Test
    void dailySummaryUsesBusinessTimezone() {
        var request = dailyRequest("evt-daily", 1_724_112_000L);
        when(eventRepository.existsByEventId("evt-daily")).thenReturn(false);
        when(dailySummaryRepository.insert(eq(request), any(PlayerDailySummaryData.class), eq(LocalDate.of(2024, 8, 20))))
            .thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isFalse();
        assertThat(result.updatedCount()).isEqualTo(1);
        verify(dailySummaryRepository).insert(eq(request), any(PlayerDailySummaryData.class), eq(LocalDate.of(2024, 8, 20)));
        verify(eventRepository).markProcessed("evt-daily");
    }

    @Test
    void rejectUnsupportedEventType() {
        var request = snapshotRequest("evt-bad-type", 100L, "10.00");
        request.setEventType("player.deleted");

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void databaseInitializedMarksSyncingAndInvalidatesOldData() {
        var request = controlRequest(
            "evt-init",
            RobotSyncSupport.EVENT_DATABASE_INITIALIZED,
            RobotSyncSupport.REASON_DATABASE_RESET,
            "1783970184_1041");
        when(eventRepository.existsByEventId("evt-init")).thenReturn(false);
        when(snapshotRepository.invalidateOldGenerations("@25EFG6M5CC", "1783970184_1041")).thenReturn(3);
        when(rebateRequestRepository.invalidateOldGenerationRequests("@25EFG6M5CC", "1783970184_1041")).thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isFalse();
        verify(runtimeStateRepository).markSyncing("@25EFG6M5CC", "1783970184_1041", 1783970184L);
        verify(snapshotRepository).invalidateOldGenerations("@25EFG6M5CC", "1783970184_1041");
        verify(rebateRequestRepository).invalidateOldGenerationRequests("@25EFG6M5CC", "1783970184_1041");
        verify(eventRepository).markProcessed("evt-init");
    }

    @Test
    void fullSnapshotCompletedMarksReadyAllowingZeroPlayers() {
        var request = controlRequest(
            "evt-done",
            RobotSyncSupport.EVENT_FULL_SNAPSHOT_COMPLETED,
            RobotSyncSupport.REASON_FULL_REBUILD_COMPLETED,
            "1783970184_1041");
        request.setData(JsonNodeFactory.instance.objectNode().put("playerCount", 0));
        when(eventRepository.existsByEventId("evt-done")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("@25EFG6M5CC")).thenReturn(Optional.of(
            new RobotRuntimeState("@25EFG6M5CC", "1783970184_1041", "SYNCING", 0, null, null, null)));
        when(runtimeStateRepository.markReady(eq("@25EFG6M5CC"), eq("1783970184_1041"), eq(0), anyLong()))
            .thenReturn(true);

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isFalse();
        verify(runtimeStateRepository).markReady("@25EFG6M5CC", "1783970184_1041", 0, 1783970184L);
        verify(eventRepository).markProcessed("evt-done");
    }

    @Test
    void fullSnapshotCompletedRejectsWhenNotSyncing() {
        var request = controlRequest(
            "evt-done-bad",
            RobotSyncSupport.EVENT_FULL_SNAPSHOT_COMPLETED,
            RobotSyncSupport.REASON_FULL_REBUILD_COMPLETED,
            "1783970184_1041");
        request.setData(JsonNodeFactory.instance.objectNode().put("playerCount", 1));
        when(eventRepository.existsByEventId("evt-done-bad")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("@25EFG6M5CC")).thenReturn(Optional.of(
            new RobotRuntimeState("@25EFG6M5CC", "1783970184_1041", "READY", 0, null, null, null)));

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class);
        verify(runtimeStateRepository, never()).markReady(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void insertUpdownRecordWithoutTouchingSnapshot() {
        var request = updownRequest("evt-updown", "rec-001", "UP");
        when(eventRepository.existsByEventId("evt-updown")).thenReturn(false);
        when(updownRecordRepository.insert(eq(request), any(PlayerUpdownRecordData.class))).thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isFalse();
        assertThat(result.updatedCount()).isEqualTo(1);
        verify(updownRecordRepository).insert(eq(request), any(PlayerUpdownRecordData.class));
        verify(snapshotRepository, never()).insert(any(), any(), anyString());
        verify(snapshotRepository, never()).update(any(), any(), anyString());
        verify(eventRepository).markProcessed("evt-updown");
    }

    @Test
    void duplicateUpdownEventIdSkipsInsert() {
        var request = updownRequest("evt-updown-dup", "rec-002", "DOWN");
        when(eventRepository.existsByEventId("evt-updown-dup")).thenReturn(true);

        var result = service.doProcess(request, false);

        assertThat(result.duplicated()).isTrue();
        verify(updownRecordRepository, never()).insert(any(), any());
        verify(snapshotRepository, never()).insert(any(), any(), anyString());
        verify(snapshotRepository, never()).update(any(), any(), anyString());
    }

    @Test
    void rejectInvalidUpdownDirection() {
        var request = updownRequest("evt-updown-bad-dir", "rec-003", "up");
        when(eventRepository.existsByEventId("evt-updown-bad-dir")).thenReturn(false);

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(updownRecordRepository, never()).insert(any(), any());
    }

    @Test
    void rejectMissingUpdownRecordId() {
        var request = updownRequest("evt-updown-no-rec", "rec-004", "UP");
        var data = JsonNodeFactory.instance.objectNode()
            .put("wxid", "wxid_001")
            .put("direction", "UP")
            .put("amount", "100.00")
            .put("balanceDelta", "100.00")
            .put("balanceAfter", "200.00")
            .put("totalUpAfter", "300.00")
            .put("totalDownAfter", "50.00")
            .put("approvedAt", 1_724_006_400L);
        request.setData(data);
        when(eventRepository.existsByEventId("evt-updown-no-rec")).thenReturn(false);

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(updownRecordRepository, never()).insert(any(), any());
    }

    @Test
    void updownRecordIdConflictIsIdempotentSuccess() {
        var request = updownRequest("evt-updown-conflict", "rec-005", "DOWN");
        when(eventRepository.existsByEventId("evt-updown-conflict")).thenReturn(false);
        when(updownRecordRepository.insert(eq(request), any(PlayerUpdownRecordData.class)))
            .thenThrow(new DuplicateKeyException("uk_robot_updown_record"));

        var result = service.doProcess(request, false);

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isTrue();
        verify(eventRepository).markProcessed("evt-updown-conflict");
    }

    @Test
    void processBatchAcceptsMixedEventsAndSkipsDuplicates() {
        var snap = snapshotRequest("evt-b1", 100L, "10.00");
        snap.setDatabaseGeneration("gen-1");
        when(eventRepository.existsByEventId("evt-b1")).thenReturn(false);
        when(runtimeStateRepository.findByRobotId("group_001")).thenReturn(Optional.of(
            new RobotRuntimeState("group_001", "gen-1", "READY", 0, null, null, null)));
        when(snapshotRepository.findVersion("group_001", "gen-1", "wxid_001")).thenReturn(Optional.empty());
        when(snapshotRepository.insert(eq(snap), any(PlayerSnapshotData.class), eq("gen-1"))).thenReturn(1);

        var dup = snapshotRequest("evt-b2", 100L, "10.00");
        when(eventRepository.existsByEventId("evt-b2")).thenReturn(true);

        var batch = new RobotSyncBatchRequest();
        batch.setProtocolVersion("1.0");
        batch.setBatch(true);
        batch.setBatchId("lane_1_1");
        batch.setLane(1);
        batch.setEventCount(2);
        batch.setEvents(java.util.List.of(snap, dup));

        var result = service.processBatch(batch, "ABCD****IJKL");
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.duplicates()).isEqualTo(1);
        verify(eventRepository).markProcessed("evt-b1");
    }

    @Test
    void processBatchRejectsControlEvents() {
        var control = snapshotRequest("evt-ctrl", 1L, "1.00");
        control.setEventType(RobotSyncSupport.EVENT_DATABASE_INITIALIZED);
        control.setSyncReason(RobotSyncSupport.REASON_DATABASE_RESET);
        control.setDatabaseGeneration("gen-1");
        control.setData(JsonNodeFactory.instance.objectNode());

        var batch = new RobotSyncBatchRequest();
        batch.setProtocolVersion("1.0");
        batch.setBatch(true);
        batch.setBatchId("lane_1_ctrl");
        batch.setLane(1);
        batch.setEventCount(1);
        batch.setEvents(java.util.List.of(control));

        assertThatThrownBy(() -> service.processBatch(batch, "ABCD****IJKL"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                .contains("control events"));
    }

    @Test
    void equalSourceUpdatedAtUsesEventIdTieBreak() {
        var older = new RobotPlayerSnapshotRepository.SnapshotVersion(100L, "evt-a");
        var req = snapshotRequest("evt-b", 100L, "1.00");
        assertThat(RobotSyncService.isIncomingSnapshotNewer(req, older)).isTrue();
        req.setEventId("evt-a");
        assertThat(RobotSyncService.isIncomingSnapshotNewer(req, older)).isFalse();
    }

    @Test
    void normalizeApprovedAtMillisecondsBeforeInsert() {
        var request = updownRequest("evt-updown-ms", "OP_UP_smoke_001", "UP");
        ObjectNode data = (ObjectNode) request.getData();
        data.put("approvedAt", 1_720_000_000_000L);
        data.put("status", "APPROVED");
        data.put("approvalSource", "MANUAL");
        when(eventRepository.existsByEventId("evt-updown-ms")).thenReturn(false);
        when(updownRecordRepository.insert(eq(request), any(PlayerUpdownRecordData.class))).thenReturn(1);

        var result = service.doProcess(request, false);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<PlayerUpdownRecordData> captor = ArgumentCaptor.forClass(PlayerUpdownRecordData.class);
        verify(updownRecordRepository).insert(eq(request), captor.capture());
        assertThat(captor.getValue().getApprovedAt()).isEqualTo(1_720_000_000L);
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(captor.getValue().getApprovalSource()).isEqualTo("MANUAL");
        verify(snapshotRepository, never()).insert(any(), any(), anyString());
        verify(snapshotRepository, never()).update(any(), any(), anyString());
    }

    @Test
    void rejectNonPositiveUpdownAmount() {
        var request = updownRequest("evt-updown-amt", "rec-amt", "UP");
        ((ObjectNode) request.getData()).put("amount", "0");
        when(eventRepository.existsByEventId("evt-updown-amt")).thenReturn(false);

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                var rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(rse.getReason()).isEqualTo("data.amount must be > 0");
            });
        verify(updownRecordRepository, never()).insert(any(), any());
    }

    @Test
    void rejectUpDirectionWithNonPositiveBalanceDelta() {
        var request = updownRequest("evt-updown-up-delta", "rec-up-delta", "UP");
        ((ObjectNode) request.getData()).put("balanceDelta", "-100.00");
        when(eventRepository.existsByEventId("evt-updown-up-delta")).thenReturn(false);

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                var rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(rse.getReason()).isEqualTo("data.balanceDelta must be > 0 when direction=UP");
            });
        verify(updownRecordRepository, never()).insert(any(), any());
    }

    @Test
    void rejectDownDirectionWithNonNegativeBalanceDelta() {
        var request = updownRequest("evt-updown-down-delta", "rec-down-delta", "DOWN");
        ((ObjectNode) request.getData()).put("balanceDelta", "100.00");
        when(eventRepository.existsByEventId("evt-updown-down-delta")).thenReturn(false);

        assertThatThrownBy(() -> service.doProcess(request, false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                var rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(rse.getReason()).isEqualTo("data.balanceDelta must be < 0 when direction=DOWN");
            });
        verify(updownRecordRepository, never()).insert(any(), any());
    }

    @Test
    void normalizeApprovedAtSecondsHelper() {
        assertThat(RobotSyncService.normalizeApprovedAtSeconds(1_720_000_000L)).isEqualTo(1_720_000_000L);
        assertThat(RobotSyncService.normalizeApprovedAtSeconds(1_720_000_000_000L)).isEqualTo(1_720_000_000L);
    }

    private RobotSyncRequest snapshotRequest(String eventId, long sourceUpdatedAt, String balance) {
        var request = baseRequest(eventId, sourceUpdatedAt, RobotSyncSupport.EVENT_SNAPSHOT_UPSERT, "flow_update");
        request.setData(JsonNodeFactory.instance.objectNode()
            .put("wxid", "wxid_001")
            .put("playerNo", "10001")
            .put("nickname", "昵称")
            .put("displayName", "显示名称")
            .put("playerType", "0")
            .put("balance", balance)
            .put("levelNo", 1)
            .put("active", true)
            .put("rebateRateUnit", "per_10000"));
        return request;
    }

    private RobotSyncRequest dailyRequest(String eventId, long businessTimestamp) {
        var request = baseRequest(eventId, businessTimestamp, RobotSyncSupport.EVENT_DAILY_SUMMARY, "daily_archive");
        request.setBusinessTimestamp(businessTimestamp);
        request.setData(JsonNodeFactory.instance.objectNode()
            .put("wxid", "wxid_001")
            .put("playerNo", "10001")
            .put("playerType", "0")
            .put("balance", "100.00")
            .put("pendingRebate", "14.00")
            .put("levelNo", 1)
            .put("active", true)
            .put("rebateRateUnit", "per_10000"));
        return request;
    }

    private RobotSyncRequest controlRequest(String eventId, String eventType, String syncReason, String generation) {
        var request = new RobotSyncRequest();
        request.setProtocolVersion("1.0");
        request.setEventId(eventId);
        request.setEventType(eventType);
        request.setRobotId("@25EFG6M5CC");
        request.setDatabaseGeneration(generation);
        request.setPlayerGroupId("@25EFG6M5CC");
        request.setStatisticsGroupId("@2ZIHG6M5CO");
        request.setEntityId("@25EFG6M5CC");
        request.setBusinessTimestamp(1783970184L);
        request.setBusinessTimezone("+08:00");
        request.setSourceUpdatedAt(1783970184L);
        request.setSyncReason(syncReason);
        request.setData(JsonNodeFactory.instance.objectNode()
            .put("reset", true)
            .put("scope", "single_robot"));
        return request;
    }

    private RobotSyncRequest updownRequest(String eventId, String recordId, String direction) {
        var request = baseRequest(eventId, 1_724_006_400L, RobotSyncSupport.EVENT_UPDOWN_RECORDED, "updown_approve");
        request.setData(JsonNodeFactory.instance.objectNode()
            .put("recordId", recordId)
            .put("wxid", "wxid_001")
            .put("playerNo", "10001")
            .put("nickname", "昵称")
            .put("direction", direction)
            .put("amount", "100.00")
            .put("balanceDelta", "DOWN".equals(direction) ? "-100.00" : "100.00")
            .put("balanceAfter", "200.00")
            .put("totalUpAfter", "300.00")
            .put("totalDownAfter", "50.00")
            .put("approvedAt", 1_724_006_400L));
        return request;
    }

    private RobotSyncRequest baseRequest(String eventId, long sourceUpdatedAt, String eventType, String syncReason) {
        var request = new RobotSyncRequest();
        request.setProtocolVersion("1.0");
        request.setEventId(eventId);
        request.setEventType(eventType);
        request.setPlayerGroupId("group_001");
        request.setStatisticsGroupId("stats_001");
        request.setEntityId("wxid_001");
        request.setBusinessTimestamp(1_724_006_400L);
        request.setBusinessTimezone("+08:00");
        request.setSourceUpdatedAt(sourceUpdatedAt);
        request.setSyncReason(syncReason);
        return request;
    }
}
