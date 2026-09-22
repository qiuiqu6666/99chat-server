package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RobotSyncControllerTest {

    @Mock
    private RobotSyncService robotSyncService;

    @Mock
    private RobotMachineService robotMachineService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private RobotSyncController controller;

    @BeforeEach
    void setUp() {
        controller = new RobotSyncController(robotSyncService, robotMachineService, objectMapper, validator);
    }

    @Test
    void rejectMissingMachineCode() throws Exception {
        when(robotMachineService.requireActiveMachineCode(null))
            .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing machine code"));
        var response = controller.sync(null, mockJson(singleJson()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    void rejectInvalidMachineCode() throws Exception {
        when(robotMachineService.requireActiveMachineCode("wrong"))
            .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid machine code"));
        var response = controller.sync("wrong", mockJson(singleJson()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptValidMachineCodeAndForceTenant() throws Exception {
        when(robotMachineService.requireActiveMachineCode("ABCD-EFGH-IJKL")).thenReturn("ABCD-EFGH-IJKL");
        when(robotSyncService.process(any(RobotSyncRequest.class))).thenAnswer(invocation -> {
            RobotSyncRequest req = invocation.getArgument(0);
            assertThat(req.getPlayerGroupId()).isEqualTo("ABCD-EFGH-IJKL");
            assertThat(req.getRobotId()).isEqualTo("ABCD-EFGH-IJKL");
            return RobotSyncResult.accepted(1);
        });

        var response = controller.sync("ABCD-EFGH-IJKL", mockJson(singleJson()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("updatedCount", 1);
        assertThat(response.getBody()).containsEntry("duplicate", false);
        assertThat(response.getBody()).containsEntry("message", "accepted");
    }

    @Test
    void duplicateResponse() throws Exception {
        when(robotMachineService.requireActiveMachineCode(anyString())).thenReturn("ABCD-EFGH-IJKL");
        when(robotSyncService.process(any(RobotSyncRequest.class))).thenReturn(RobotSyncResult.duplicate());

        var response = controller.sync("ABCD-EFGH-IJKL", mockJson(singleJson()));

        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("duplicate", true);
        assertThat(response.getBody()).containsEntry("updatedCount", 0);
        assertThat(response.getBody()).containsEntry("message", "already processed");
    }

    @Test
    void notUpdatedResponse() throws Exception {
        when(robotMachineService.requireActiveMachineCode(anyString())).thenReturn("ABCD-EFGH-IJKL");
        when(robotSyncService.process(any(RobotSyncRequest.class)))
            .thenReturn(RobotSyncResult.notUpdated("玩家不存在或更新0条"));

        var response = controller.sync("ABCD-EFGH-IJKL", mockJson(singleJson()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("updatedCount", 0);
        assertThat(response.getBody()).containsEntry("message", "玩家不存在或更新0条");
    }

    @Test
    void acceptBatchAndForceTenantOnEachEvent() throws Exception {
        when(robotMachineService.requireActiveMachineCode("ABCD-EFGH-IJKL")).thenReturn("ABCD-EFGH-IJKL");
        when(robotSyncService.processBatch(any(RobotSyncBatchRequest.class), eq("ABCD****IJKL")))
            .thenAnswer(invocation -> {
                RobotSyncBatchRequest batch = invocation.getArgument(0);
                assertThat(batch.getEventCount()).isEqualTo(1);
                assertThat(batch.getEvents().get(0).getPlayerGroupId()).isEqualTo("ABCD-EFGH-IJKL");
                assertThat(batch.getEvents().get(0).getRobotId()).isEqualTo("ABCD-EFGH-IJKL");
                return new RobotSyncBatchResult(batch.getBatchId(), 1, 0);
            });

        var response = controller.sync("ABCD-EFGH-IJKL", mockJson(batchJson()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("batchId", "lane_3_1");
        assertThat(response.getBody()).containsEntry("accepted", 1);
    }

    @Test
    void rejectBatchWhenEventCountMismatch() throws Exception {
        when(robotMachineService.requireActiveMachineCode(anyString())).thenReturn("ABCD-EFGH-IJKL");
        String bad = """
            {"protocolVersion":"1.0","batch":true,"batchId":"b1","lane":1,"eventCount":2,"events":[%s]}
            """.formatted(eventJson());
        var response = controller.sync("ABCD-EFGH-IJKL", mockJson(bad));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    private static MockHttpServletRequest mockJson(String json) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent(json.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        return req;
    }

    private static String singleJson() {
        return """
            {"protocolVersion":"1.0","eventId":"evt-1","eventType":"player.snapshot.upsert",
             "playerGroupId":"group_001","statisticsGroupId":"stats_001","entityId":"wxid_001",
             "businessTimestamp":1724006400,"businessTimezone":"+08:00","sourceUpdatedAt":100,
             "syncReason":"flow_update","data":{"wxid":"wxid_001"}}
            """;
    }

    private static String eventJson() {
        return """
            {"protocolVersion":"1.0","eventId":"evt-1","eventType":"player.snapshot.upsert",
             "playerGroupId":"group_001","statisticsGroupId":"stats_001","entityId":"wxid_001",
             "businessTimestamp":1724006400,"businessTimezone":"+08:00","sourceUpdatedAt":100,
             "syncReason":"flow_update","data":{"wxid":"wxid_001"}}
            """;
    }

    private static String batchJson() {
        return """
            {"protocolVersion":"1.0","batch":true,"batchId":"lane_3_1","lane":3,"eventCount":1,"events":[%s]}
            """.formatted(eventJson());
    }
}
