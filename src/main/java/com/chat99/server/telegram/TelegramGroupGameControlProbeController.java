package com.chat99.server.telegram;

import com.chat99.server.group.GroupGameService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维探活：模拟飞机群「配对 / 开启」开群特权指令，不走 Telegram 轮询。
 * 鉴权使用 {@code X-Robot-Secret} 头，密钥 {@code TELEGRAM_PROBE_SECRET}
 * （未配置时回退 {@code ROBOT_SYNC_SECRET}）。
 */
@RestController
public class TelegramGroupGameControlProbeController {

    private final String probeSecret;
    private final TelegramGroupGameControlService gameControlService;
    private final GroupGameService groupGameService;

    public TelegramGroupGameControlProbeController(
            @Value("${chat99.telegram.probe-secret:}") String probeSecret,
            TelegramGroupGameControlService gameControlService,
            GroupGameService groupGameService) {
        this.probeSecret = probeSecret;
        this.gameControlService = gameControlService;
        this.groupGameService = groupGameService;
    }

    @PostMapping("/api/internal/telegram-game-control")
    public ResponseEntity<Map<String, Object>> probe(
            @RequestHeader(value = "X-Robot-Secret", required = false) String secret,
            @RequestBody ProbeBody body) {
        if (!validSecret(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "invalid robot secret"));
        }
        String text = body == null ? null : body.text();
        String chatId = body != null && body.chatId() != null && !body.chatId().isBlank()
            ? body.chatId().trim()
            : "probe";
        String reply = gameControlService.handle(chatId, text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", reply != null);
        out.put("reply", reply);
        if (body != null && body.checkGroupId() != null && !body.checkGroupId().isBlank()) {
            out.put("checkGroupId", body.checkGroupId());
            out.put("gameEnabled", groupGameService.isGameEnabled(body.checkGroupId().trim()));
        }
        return ResponseEntity.ok(out);
    }

    private boolean validSecret(String requestSecret) {
        if (requestSecret == null || requestSecret.isBlank()
            || probeSecret == null || probeSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            requestSecret.getBytes(StandardCharsets.UTF_8),
            probeSecret.getBytes(StandardCharsets.UTF_8));
    }

    public record ProbeBody(String text, String checkGroupId, String chatId) {}
}
