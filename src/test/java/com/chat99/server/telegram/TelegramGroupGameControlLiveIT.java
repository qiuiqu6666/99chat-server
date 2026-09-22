package com.chat99.server.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupGameService;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * 需要可连库 + robot-service。默认用 MockBean 阻断 TG 轮询。
 * 本 IT 主要验证指令解析与选定群会话；完整 bind/enable 依赖 robot-service。
 */
@SpringBootTest
@TestPropertySource(properties = {
    "chat99.telegram-ops.enabled=true",
    "chat99.telegram-ops.game-control-enabled=true",
    "chat99.telegram-ops.game-control-chat-id=-5510962408"
})
class TelegramGroupGameControlLiveIT {

    @MockBean
    private TelegramOpsBotPoller poller;

    @Autowired
    private TelegramGroupGameControlService gameControlService;

    @Autowired
    private GroupGameService groupGameService;

    @Autowired
    private RobotMachineClient robotMachineClient;

    @Value("${chat99.telegram.probe-secret:}")
    private String probeSecret;

    @Test
    void parseNewPrivilegeCommands() {
        assertThat(TelegramGroupGameControlService.parseCommand("配对ABCD-EFGH-JKMN @TGS#2BN4LEN5C3"))
            .isPresent();
        assertThat(TelegramGroupGameControlService.parseCommand("开启@2EYHG6M5CJ"))
            .isPresent();
        assertThat(gameControlService.shouldHandleMessage("-5510962408", "配对ABCD-EFGH-JKMN", false))
            .isTrue();
        assertThat(gameControlService.shouldHandleMessage("-1004336340388", "开启@2EYHG6M5CJ", false))
            .isFalse();
    }

    @Test
    void closeStillTogglesGameEnabledWhenGroupExists() {
        String g1 = "@TGS#2BN4LEN5C3";
        Assumptions.assumeTrue(gameControlService.resolveGroupId(g1).isPresent(), "group missing in DB");
        boolean before = groupGameService.isGameEnabled(g1);
        try {
            String close = gameControlService.handle("it-chat", "关闭@" + g1.replace("@", ""));
            // 关闭@2BN4LEN5C3 形态
            if (close == null || close.contains("未找到")) {
                close = gameControlService.handle("it-chat", "关闭" + g1);
            }
            assertThat(close).contains("已关闭");
            assertThat(groupGameService.isGameEnabled(g1)).isFalse();
        } finally {
            groupGameService.setGameEnabled(g1, before);
        }
    }
}
