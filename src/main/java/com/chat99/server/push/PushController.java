package com.chat99.server.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    private final PushTokenService pushTokenService;

    public PushController(PushTokenService pushTokenService) {
        this.pushTokenService = pushTokenService;
    }

    public record RegisterBody(
        @NotBlank String deviceId,
        @NotNull PushPlatform platform,
        @NotBlank String token,
        String voipToken) {}

    public record VoipRegisterBody(
        @NotBlank String deviceId,
        @NotBlank String token) {}

    public record UnregisterBody(@NotBlank String deviceId) {}

    @PostMapping("/me/push-token")
    public Map<String, Object> register(@Valid @RequestBody RegisterBody body, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        UserPushToken row = pushTokenService.register(
            userId, body.deviceId(), body.platform(), body.token(), body.voipToken());
        log.info("push token registered userId={} deviceId={} platform={} provider={} hasVoip={}",
            userId, row.getDeviceId(), row.getPlatform(), row.getProvider(),
            row.getVoipPushToken() != null && !row.getVoipPushToken().isBlank());
        return Map.of(
            "ok", true,
            "platform", row.getPlatform().name(),
            "provider", row.getProvider().name(),
            "hasVoipToken", row.getVoipPushToken() != null && !row.getVoipPushToken().isBlank());
    }

    @PostMapping("/me/voip-push-token")
    public Map<String, Object> registerVoip(@Valid @RequestBody VoipRegisterBody body, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        UserPushToken row = pushTokenService.registerVoip(userId, body.deviceId(), body.token());
        log.info("voip push token updated userId={} deviceId={}", userId, row.getDeviceId());
        return Map.of("ok", true, "deviceId", row.getDeviceId());
    }

    @DeleteMapping("/me/push-token")
    public Map<String, Object> unregister(@Valid @RequestBody UnregisterBody body, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        pushTokenService.unregister(userId, body.deviceId());
        log.info("push token unregistered userId={} deviceId={}", userId, body.deviceId());
        return Map.of("ok", true);
    }
}
