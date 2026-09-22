package com.chat99.server.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QrLoginController {

    private final QrLoginService qrLoginService;

    public QrLoginController(QrLoginService qrLoginService) {
        this.qrLoginService = qrLoginService;
    }

    public record CreateRequest(@NotBlank String deviceId, String deviceModel) {}

    public record CreateResponse(String sessionId, String qrPayload, int expiresIn) {}

    /**
     * 未确认态不要序列化 null 的 expiresIn/token 等字段：
     * 部分客户端会把 poll 里的 expiresIn 当成二维码剩余 TTL，null/0 会立刻显示「已过期」。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PollResponse(
        String status,
        String token,
        String userId,
        Long expiresIn,
        String nextStep,
        String displayHint
    ) {}

    public record ScanRequest(@NotBlank String sessionId) {}

    public record ScanResponse(String sessionId, String status, String siteLabel) {}

    public record ConfirmRequest(@NotBlank String sessionId, @NotNull Boolean approve) {}

    public record ConfirmResponse(String sessionId, String status) {}

    @PostMapping("/auth/login/qr/session")
    public CreateResponse create(@Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        return qrLoginService.createSession(req.deviceId(), req.deviceModel(), http);
    }

    @GetMapping("/auth/login/qr/session/{sessionId}")
    public PollResponse poll(@PathVariable String sessionId) {
        return qrLoginService.poll(sessionId);
    }

    @PostMapping("/auth/login/qr/scan")
    public ScanResponse scan(Authentication auth, @Valid @RequestBody ScanRequest req) {
        String userId = (String) auth.getPrincipal();
        return qrLoginService.scan(userId, req.sessionId());
    }

    @PostMapping("/auth/login/qr/confirm")
    public ConfirmResponse confirm(Authentication auth,
                                   @Valid @RequestBody ConfirmRequest req,
                                   HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        return qrLoginService.confirm(userId, req.sessionId(), Boolean.TRUE.equals(req.approve()), http);
    }
}
