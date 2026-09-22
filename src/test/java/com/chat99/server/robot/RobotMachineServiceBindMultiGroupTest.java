package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RobotMachineServiceBindMultiGroupTest {

    @Mock RobotMachineRepository machineRepository;
    @Mock RobotGroupBindingRepository bindingRepository;
    @Mock RobotTenantCleanupRepository tenantCleanupRepository;

    RobotMachineService service;

    @BeforeEach
    void setUp() {
        service = new RobotMachineService(
            machineRepository,
            bindingRepository,
            tenantCleanupRepository,
            new org.springframework.transaction.support.TransactionTemplate());
    }

    @Test
    void bindGroupAllowsSameMachineOnSecondGroup() {
        String code = "ABCD-EFGH-JKMN";
        when(machineRepository.findByCode(code)).thenReturn(Optional.of(active(code)));
        when(bindingRepository.findByGroupId("@TGS#GROUP-B"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(binding("@TGS#GROUP-B", code)));

        RobotMachineService.BindResponse resp = service.bindGroup(code, "@TGS#GROUP-B");

        assertThat(resp.machineCode()).isEqualTo(code);
        assertThat(resp.groupId()).isEqualTo("@TGS#GROUP-B");
        verify(bindingRepository).upsertBind("@TGS#GROUP-B", code);
    }

    @Test
    void bindGroupStillRejectsGroupAlreadyBoundToOtherMachine() {
        String code = "ABCD-EFGH-JKMN";
        when(machineRepository.findByCode(code)).thenReturn(Optional.of(active(code)));
        when(bindingRepository.findByGroupId("@TGS#GROUP-B"))
            .thenReturn(Optional.of(binding("@TGS#GROUP-B", "OTHER-MACHINEX")));

        assertThatThrownBy(() -> service.bindGroup(code, "@TGS#GROUP-B"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("GROUP_ALREADY_BOUND");
    }

    @Test
    void bindGroupSameMachineCallsUpsertEvenWhenAlreadyEnabled() {
        // upsert SQL 同码保留 enabled；service 侧仍走 upsertBind（不因已开启而跳过）
        String code = "GZKH-DJ3M-VKSB";
        when(machineRepository.findByCode(code)).thenReturn(Optional.of(active(code)));
        when(bindingRepository.findByGroupId("@TGS#2QWJO3N5C3"))
            .thenReturn(Optional.of(enabledBinding("@TGS#2QWJO3N5C3", code, code)))
            .thenReturn(Optional.of(enabledBinding("@TGS#2QWJO3N5C3", code, code)));

        RobotMachineService.BindResponse resp = service.bindGroup(code, "@TGS#2QWJO3N5C3");

        assertThat(resp.enabled()).isTrue();
        assertThat(resp.robotId()).isEqualTo(code);
        verify(bindingRepository).upsertBind("@TGS#2QWJO3N5C3", code);
    }

    private static RobotMachine active(String code) {
        return new RobotMachine(code, "ACTIVE", null, LocalDateTime.now(), LocalDateTime.now());
    }

    private static RobotGroupBinding binding(String groupId, String machineCode) {
        return new RobotGroupBinding(groupId, machineCode, null, false, LocalDateTime.now(), LocalDateTime.now());
    }

    private static RobotGroupBinding enabledBinding(String groupId, String machineCode, String robotId) {
        return new RobotGroupBinding(groupId, machineCode, robotId, true, LocalDateTime.now(), LocalDateTime.now());
    }
}
