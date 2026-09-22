package com.chat99.server.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupGameService;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramGroupGameControlServiceTest {

    @Mock GroupGameService groupGameService;
    @Mock GroupProfileRepository groupProfileRepository;
    @Mock RobotMachineClient robotMachineClient;

    TelegramGroupGameControlService service;

    @BeforeEach
    void setup() {
        TelegramOpsProperties props = new TelegramOpsProperties(
            true, "token", "-1004336340388", true, true, true, true, 25,
            "-5510962408", true);
        service = new TelegramGroupGameControlService(
            props, groupGameService, groupProfileRepository, robotMachineClient);
    }

    @Test
    void parsePairAndEnable() {
        assertThat(TelegramGroupGameControlService.parseCommand("配对ABCD-EFGH-JKMN @TGS#2BN4LEN5C3"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.PAIR);
                assertThat(c.machineCode()).isEqualTo("ABCD-EFGH-JKMN");
                assertThat(c.groupToken()).isEqualTo("@TGS#2BN4LEN5C3");
            });
        assertThat(TelegramGroupGameControlService.parseCommand("配对ABCD-EFGH-JKMN"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.PAIR);
                assertThat(c.groupToken()).isNull();
            });
        assertThat(TelegramGroupGameControlService.parseCommand("开启@2EYHG6M5CJ"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.ENABLE);
                assertThat(c.robotId()).isEqualTo("@2EYHG6M5CJ");
            });
        assertThat(TelegramGroupGameControlService.parseCommand("开启@2EYHG6M5CJ @TGS#2BN4LEN5C3"))
            .hasValueSatisfying(c -> {
                assertThat(c.robotId()).isEqualTo("@2EYHG6M5CJ");
                assertThat(c.groupToken()).isEqualTo("@TGS#2BN4LEN5C3");
            });
        assertThat(TelegramGroupGameControlService.parseCommand("关闭@TGS#2BN4LEN5C3"))
            .hasValueSatisfying(c -> assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.DISABLE));
    }

    @Test
    void parseQueryByGroupIdOnly() {
        assertThat(TelegramGroupGameControlService.parseCommand("@TGS#2BN4LEN5C3"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.QUERY);
                assertThat(c.groupToken()).isEqualTo("@TGS#2BN4LEN5C3");
            });
        assertThat(TelegramGroupGameControlService.parseCommand("@TGS#_@TGS#c2SX4NMM62CZ"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.QUERY);
                assertThat(c.groupToken()).isEqualTo("@TGS#_@TGS#c2SX4NMM62CZ");
            });
        assertThat(TelegramGroupGameControlService.parseCommand("hi")).isEmpty();
    }

    @Test
    void parseEnableByFullGroupId() {
        assertThat(TelegramGroupGameControlService.parseCommand("开启@TGS#2E2U6YN5CC"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.ENABLE);
                assertThat(c.robotId()).isEqualTo("@TGS#2E2U6YN5CC");
                assertThat(c.groupToken()).isNull();
            });
        assertThat(TelegramGroupGameControlService.parseCommand("开启@TGS#_@TGS#c2SX4NMM62CZ"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.ENABLE);
                assertThat(c.robotId()).isEqualTo("@TGS#_@TGS#c2SX4NMM62CZ");
            });
        // 短码仍兼容
        assertThat(TelegramGroupGameControlService.parseCommand("开启@27PIAKM5CC"))
            .hasValueSatisfying(c -> {
                assertThat(c.type()).isEqualTo(TelegramGroupGameControlService.Type.ENABLE);
                assertThat(c.robotId()).isEqualTo("@27PIAKM5CC");
            });
    }

    @Test
    void handleQueryLeadsWithGameSwitchStatus() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#2E2U6YN5CC")).thenReturn(true);
        when(groupGameService.isGameEnabled("@TGS#2E2U6YN5CC")).thenReturn(true);
        when(robotMachineClient.groupStatus("@TGS#2E2U6YN5CC"))
            .thenReturn(Map.of("bound", true, "enabled", true, "machineCode", "CP5Y-4EX1-AC9V", "robotId", "CP5Y-4EX1-AC9V"));
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#2E2U6YN5CC");
        profile.setGroupName("完整ID群");
        when(groupProfileRepository.findById("@TGS#2E2U6YN5CC")).thenReturn(Optional.of(profile));

        String reply = service.handle("tg-chat", "@TGS#2E2U6YN5CC");
        assertThat(reply).startsWith("✅ 游戏开关: <b>已开启</b>")
            .contains("@TGS#2E2U6YN5CC")
            .contains("完整ID群");
    }

    @Test
    void handlePairThenEnableByFullGroupId() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#2E2U6YN5CC")).thenReturn(true);
        when(robotMachineClient.groupStatus("@TGS#2E2U6YN5CC"))
            .thenReturn(Map.of("bound", false, "enabled", false));
        when(robotMachineClient.bindGroup("CP5Y-4EX1-AC9V", "@TGS#2E2U6YN5CC"))
            .thenReturn(Map.of("success", true, "machineCode", "CP5Y-4EX1-AC9V"));
        when(robotMachineClient.enableForGroup("@TGS#2E2U6YN5CC", "CP5Y-4EX1-AC9V"))
            .thenReturn(Map.of(
                "success", true,
                "machineCode", "CP5Y-4EX1-AC9V",
                "robotId", "CP5Y-4EX1-AC9V"));
        when(groupGameService.setGameEnabled("@TGS#2E2U6YN5CC", true)).thenReturn(true);
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#2E2U6YN5CC");
        profile.setGroupName("完整开启群");
        when(groupProfileRepository.findById("@TGS#2E2U6YN5CC")).thenReturn(Optional.of(profile));

        String pair = service.handle("tg-chat", "配对CP5Y-4EX1-AC9V");
        assertThat(pair).contains("已选定机器码");

        String enable = service.handle("tg-chat", "开启@TGS#2E2U6YN5CC");
        assertThat(enable).contains("开群特权成功").contains("@TGS#2E2U6YN5CC");
        verify(robotMachineClient).bindGroup("CP5Y-4EX1-AC9V", "@TGS#2E2U6YN5CC");
        verify(robotMachineClient).enableForGroup("@TGS#2E2U6YN5CC", "CP5Y-4EX1-AC9V");
        verify(groupGameService).setGameEnabled("@TGS#2E2U6YN5CC", true);
    }

    @Test
    void extractCombinedPairAndEnableFullGroupIdOnSameLine() {
        assertThat(TelegramGroupGameControlService.extractCommandSegments(
            "配对CP5Y-4EX1-AC9V 开启@TGS#27PIAKM5CC"))
            .containsExactly("配对CP5Y-4EX1-AC9V", "开启@TGS#27PIAKM5CC");
        assertThat(TelegramGroupGameControlService.extractCommandSegments(
            "配对CP5Y-4EX1-AC9V 开启@TGS#_@TGS#c2SX4NMM62CZ"))
            .containsExactly("配对CP5Y-4EX1-AC9V", "开启@TGS#_@TGS#c2SX4NMM62CZ");
    }

    @Test
    void handlePairThenEnableUsesSelectedGroup() {
        when(groupProfileRepository.existsById("@TGS#2BN4LEN5C3")).thenReturn(true);
        when(robotMachineClient.bindGroup("ABCD-EFGH-JKMN", "@TGS#2BN4LEN5C3"))
            .thenReturn(Map.of("success", true, "machineCode", "ABCD-EFGH-JKMN"));
        when(robotMachineClient.enableForGroup("@TGS#2BN4LEN5C3", "@2EYHG6M5CJ"))
            .thenReturn(Map.of("success", true, "machineCode", "ABCD-EFGH-JKMN", "robotId", "@2EYHG6M5CJ"));
        when(groupGameService.setGameEnabled("@TGS#2BN4LEN5C3", true)).thenReturn(true);
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#2BN4LEN5C3");
        profile.setGroupName("测试群");
        when(groupProfileRepository.findById("@TGS#2BN4LEN5C3")).thenReturn(Optional.of(profile));

        String pair = service.handle("tg-chat", "配对ABCD-EFGH-JKMN @TGS#2BN4LEN5C3");
        assertThat(pair).contains("配对成功").contains("测试群");

        String enable = service.handle("tg-chat", "开启@2EYHG6M5CJ");
        assertThat(enable).contains("开群特权成功").contains("@2EYHG6M5CJ");
        verify(groupGameService).setGameEnabled("@TGS#2BN4LEN5C3", true);
        verify(robotMachineClient).enableForGroup("@TGS#2BN4LEN5C3", "@2EYHG6M5CJ");
    }

    @Test
    void handlePairMachineThenEnableByGroupShortId() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#27PIAKM5CC")).thenReturn(true);
        when(robotMachineClient.bindGroup("CP5Y-4EX1-AC9V", "@TGS#27PIAKM5CC"))
            .thenReturn(Map.of("success", true, "machineCode", "CP5Y-4EX1-AC9V"));
        when(robotMachineClient.enableForGroup("@TGS#27PIAKM5CC", "CP5Y-4EX1-AC9V"))
            .thenReturn(Map.of(
                "success", true,
                "machineCode", "CP5Y-4EX1-AC9V",
                "robotId", "CP5Y-4EX1-AC9V"));
        when(groupGameService.setGameEnabled("@TGS#27PIAKM5CC", true)).thenReturn(true);
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#27PIAKM5CC");
        profile.setGroupName("目标群");
        when(groupProfileRepository.findById("@TGS#27PIAKM5CC")).thenReturn(Optional.of(profile));

        String pair = service.handle("tg-chat", "配对CP5Y-4EX1-AC9V");
        assertThat(pair).contains("已选定机器码").contains("CP5Y-4EX1-AC9V").contains("开启@TGS#");

        String enable = service.handle("tg-chat", "开启@27PIAKM5CC");
        assertThat(enable).contains("开群特权成功").contains("目标群").contains("CP5Y-4EX1-AC9V");
        verify(robotMachineClient).bindGroup("CP5Y-4EX1-AC9V", "@TGS#27PIAKM5CC");
        verify(robotMachineClient).enableForGroup("@TGS#27PIAKM5CC", "CP5Y-4EX1-AC9V");
        verify(groupGameService).setGameEnabled("@TGS#27PIAKM5CC", true);
    }

    @Test
    void handleEnableByGroupWithoutPairAsksForMachine() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#27PIAKM5CC")).thenReturn(true);
        when(robotMachineClient.groupStatus("@TGS#27PIAKM5CC"))
            .thenReturn(Map.of("bound", false, "enabled", false));

        String reply = service.handle("tg-chat", "开启@27PIAKM5CC");
        assertThat(reply).contains("请先发送机器码");
    }

    @Test
    void extractCombinedPairAndEnableOnSameLine() {
        assertThat(TelegramGroupGameControlService.extractCommandSegments(
            "配对CP5Y-4EX1-AC9V 开启@27PIAKM5CC"))
            .containsExactly("配对CP5Y-4EX1-AC9V", "开启@27PIAKM5CC");
        assertThat(TelegramGroupGameControlService.extractCommandSegments(
            "配对CP5Y-4EX1-AC9V\n开启@27PIAKM5CC"))
            .containsExactly("配对CP5Y-4EX1-AC9V", "开启@27PIAKM5CC");
    }

    @Test
    void handleCombinedPairAndEnableRepliesBoth() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#27PIAKM5CC")).thenReturn(true);
        when(robotMachineClient.groupStatus("@TGS#27PIAKM5CC"))
            .thenReturn(Map.of("bound", false, "enabled", false));
        when(robotMachineClient.bindGroup("CP5Y-4EX1-AC9V", "@TGS#27PIAKM5CC"))
            .thenReturn(Map.of("success", true, "machineCode", "CP5Y-4EX1-AC9V"));
        when(robotMachineClient.enableForGroup("@TGS#27PIAKM5CC", "CP5Y-4EX1-AC9V"))
            .thenReturn(Map.of(
                "success", true,
                "machineCode", "CP5Y-4EX1-AC9V",
                "robotId", "CP5Y-4EX1-AC9V"));
        when(groupGameService.setGameEnabled("@TGS#27PIAKM5CC", true)).thenReturn(true);
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#27PIAKM5CC");
        profile.setGroupName("目标群");
        when(groupProfileRepository.findById("@TGS#27PIAKM5CC")).thenReturn(Optional.of(profile));

        String reply = service.handle("tg-chat", "配对CP5Y-4EX1-AC9V 开启@27PIAKM5CC");
        assertThat(reply).contains("已选定机器码").contains("开群特权成功");
    }

    @Test
    void pairAloneAfterQueryDoesNotBindSelectedGroup() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#2QWJO3N5C3")).thenReturn(true);
        when(groupGameService.isGameEnabled("@TGS#2QWJO3N5C3")).thenReturn(true);
        when(robotMachineClient.groupStatus("@TGS#2QWJO3N5C3"))
            .thenReturn(Map.of("bound", true, "enabled", true, "machineCode", "GZKH-DJ3M-VKSB"));
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#2QWJO3N5C3");
        profile.setGroupName("旧群");
        when(groupProfileRepository.findById("@TGS#2QWJO3N5C3")).thenReturn(Optional.of(profile));

        service.handle("tg-chat", "@TGS#2QWJO3N5C3");
        String pair = service.handle("tg-chat", "配对GZKH-DJ3M-VKSB");

        assertThat(pair).contains("已选定机器码").contains("GZKH-DJ3M-VKSB");
        verify(robotMachineClient, never()).bindGroup(anyString(), anyString());
    }

    @Test
    void combinedPairEnableNewGroupDoesNotBindPreviouslyQueriedGroup() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#2QWJO3N5C3")).thenReturn(true);
        when(groupProfileRepository.existsById("@TGS#2IOSS3N5CY")).thenReturn(true);
        when(groupGameService.isGameEnabled("@TGS#2QWJO3N5C3")).thenReturn(true);
        when(robotMachineClient.groupStatus("@TGS#2QWJO3N5C3"))
            .thenReturn(Map.of("bound", true, "enabled", true, "machineCode", "GZKH-DJ3M-VKSB"));
        when(robotMachineClient.groupStatus("@TGS#2IOSS3N5CY"))
            .thenReturn(Map.of("bound", false, "enabled", false));
        when(robotMachineClient.bindGroup("GZKH-DJ3M-VKSB", "@TGS#2IOSS3N5CY"))
            .thenReturn(Map.of("success", true, "machineCode", "GZKH-DJ3M-VKSB"));
        when(robotMachineClient.enableForGroup("@TGS#2IOSS3N5CY", "GZKH-DJ3M-VKSB"))
            .thenReturn(Map.of(
                "success", true,
                "machineCode", "GZKH-DJ3M-VKSB",
                "robotId", "GZKH-DJ3M-VKSB"));
        when(groupGameService.setGameEnabled("@TGS#2IOSS3N5CY", true)).thenReturn(true);
        GroupProfile oldGroup = new GroupProfile();
        oldGroup.setGroupId("@TGS#2QWJO3N5C3");
        oldGroup.setGroupName("旧群");
        when(groupProfileRepository.findById("@TGS#2QWJO3N5C3")).thenReturn(Optional.of(oldGroup));
        GroupProfile newGroup = new GroupProfile();
        newGroup.setGroupId("@TGS#2IOSS3N5CY");
        newGroup.setGroupName("新群");
        when(groupProfileRepository.findById("@TGS#2IOSS3N5CY")).thenReturn(Optional.of(newGroup));

        service.handle("tg-chat", "@TGS#2QWJO3N5C3");
        String reply = service.handle("tg-chat", "配对GZKH-DJ3M-VKSB 开启@TGS#2IOSS3N5CY");

        assertThat(reply).contains("已选定机器码").contains("开群特权成功").contains("新群");
        verify(robotMachineClient, never()).bindGroup("GZKH-DJ3M-VKSB", "@TGS#2QWJO3N5C3");
        verify(robotMachineClient).bindGroup("GZKH-DJ3M-VKSB", "@TGS#2IOSS3N5CY");
        verify(robotMachineClient).enableForGroup("@TGS#2IOSS3N5CY", "GZKH-DJ3M-VKSB");
    }

    @Test
    void resolveBothRealGroupIdShapes() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#2BN4LEN5C3")).thenReturn(true);
        assertThat(service.resolveGroupId("@TGS#2BN4LEN5C3")).contains("@TGS#2BN4LEN5C3");
        assertThat(service.resolveGroupId("2BN4LEN5C3")).contains("@TGS#2BN4LEN5C3");

        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#_@TGS#cGN5PNMM62CD")).thenReturn(true);
        assertThat(service.resolveGroupId("@TGS#_@TGS#cGN5PNMM62CD")).contains("@TGS#_@TGS#cGN5PNMM62CD");
        assertThat(service.resolveGroupId("cGN5PNMM62CD")).contains("@TGS#_@TGS#cGN5PNMM62CD");
    }

    @Test
    void resolveMigratedCustomMId_doesNotWrapAsCommunity() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("m2ERSS3N5CX")).thenReturn(true);
        assertThat(service.resolveGroupId("m2ERSS3N5CX")).contains("m2ERSS3N5CX");
        assertThat(service.resolveGroupId("@TGS#_@TGS#m2ERSS3N5CX")).contains("m2ERSS3N5CX");
    }

    @Test
    void chatIdMatchesWithAndWithout100Prefix() {
        assertThat(TelegramGroupGameControlService.chatIdMatches("-5510962408", "-5510962408")).isTrue();
        assertThat(TelegramGroupGameControlService.chatIdMatches("-1005510962408", "-5510962408")).isTrue();
        assertThat(TelegramGroupGameControlService.chatIdMatches("-1004336340388", "-5510962408")).isFalse();
    }

    @Test
    void handleDisableOnlyTogglesGameFlag() {
        when(groupProfileRepository.existsById(anyString())).thenReturn(false);
        when(groupProfileRepository.existsById("@TGS#_@TGS#cGN5PNMM62CD")).thenReturn(true);
        when(groupGameService.setGameEnabled("@TGS#_@TGS#cGN5PNMM62CD", false)).thenReturn(false);
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#_@TGS#cGN5PNMM62CD");
        profile.setGroupName("京东测试");
        when(groupProfileRepository.findById("@TGS#_@TGS#cGN5PNMM62CD")).thenReturn(Optional.of(profile));

        String reply = service.handle("tg", "关闭@TGS#_@TGS#cGN5PNMM62CD");

        assertThat(reply).contains("已关闭").contains("京东测试");
        verify(groupGameService).setGameEnabled(eq("@TGS#_@TGS#cGN5PNMM62CD"), eq(false));
    }
}
