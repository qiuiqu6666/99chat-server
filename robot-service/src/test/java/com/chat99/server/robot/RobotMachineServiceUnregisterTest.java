package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RobotMachineServiceUnregisterTest {

    @Mock RobotMachineRepository machineRepository;
    @Mock RobotGroupBindingRepository bindingRepository;
    @Mock RobotTenantCleanupRepository tenantCleanupRepository;
    @Mock TransactionTemplate robotTransactionTemplate;

    RobotMachineService service;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(robotTransactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new RobotMachineService(
            machineRepository,
            bindingRepository,
            tenantCleanupRepository,
            robotTransactionTemplate);
    }

    @Test
    void unregisterCascadesTenantThenBindingsThenMachine() {
        String code = "ABCD-EFGH-JKMN";
        when(machineRepository.findByCode(code)).thenReturn(Optional.of(active(code)));
        when(tenantCleanupRepository.deleteUpdownRecords(code)).thenReturn(1);
        when(tenantCleanupRepository.deleteDailySummaries(code)).thenReturn(2);
        when(tenantCleanupRepository.deleteSnapshots(code)).thenReturn(3);
        when(tenantCleanupRepository.deleteSyncEvents(code)).thenReturn(4);
        when(tenantCleanupRepository.deleteRuntimeStates(code)).thenReturn(1);
        when(tenantCleanupRepository.deleteExportTasks(code)).thenReturn(0);
        when(bindingRepository.deleteByMachineCode(code)).thenReturn(2);
        when(machineRepository.deleteByCode(code)).thenReturn(1);

        RobotMachineService.UnregisterResponse resp = service.unregister(code);

        assertThat(resp.machineCode()).isEqualTo(code);
        assertThat(resp.deletedBindings()).isEqualTo(2);
        assertThat(resp.deletedSnapshots()).isEqualTo(3);
        assertThat(resp.deletedDailySummaries()).isEqualTo(2);
        assertThat(resp.deletedUpdownRecords()).isEqualTo(1);
        assertThat(resp.deletedRuntimeStates()).isEqualTo(1);
        assertThat(resp.deletedSyncEvents()).isEqualTo(4);
        assertThat(resp.deletedExportTasks()).isEqualTo(0);

        InOrder order = Mockito.inOrder(tenantCleanupRepository, bindingRepository, machineRepository);
        order.verify(tenantCleanupRepository).deleteUpdownRecords(code);
        order.verify(tenantCleanupRepository).deleteDailySummaries(code);
        order.verify(tenantCleanupRepository).deleteSnapshots(code);
        order.verify(tenantCleanupRepository).deleteSyncEvents(code);
        order.verify(tenantCleanupRepository).deleteRuntimeStates(code);
        order.verify(tenantCleanupRepository).deleteExportTasks(code);
        order.verify(bindingRepository).deleteByMachineCode(code);
        order.verify(machineRepository).deleteByCode(code);
    }

    @Test
    void unregisterRejectsUnknownMachineCode() {
        when(machineRepository.findByCode("NOPE-NOPE-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unregister("NOPE-NOPE-NOPE"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                var rse = (ResponseStatusException) ex;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(rse.getReason()).isEqualTo("invalid machine code");
            });
    }

    private static RobotMachine active(String code) {
        return new RobotMachine(code, "ACTIVE", null, LocalDateTime.now(), LocalDateTime.now());
    }
}
