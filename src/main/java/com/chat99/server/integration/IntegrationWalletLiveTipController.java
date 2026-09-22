package com.chat99.server.integration;

import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import io.jsonwebtoken.JwtException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/integration/v1/wallet")
public class IntegrationWalletLiveTipController {

    private final IntegrationAuthService authService;
    private final IntegrationWalletLiveTipService tipService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserSessionService sessionService;

    public IntegrationWalletLiveTipController(
        IntegrationAuthService authService,
        IntegrationWalletLiveTipService tipService,
        JwtService jwtService,
        UserRepository userRepository,
        UserSessionService sessionService
    ) {
        this.authService = authService;
        this.tipService = tipService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
    }

    public record LiveTipRequest(
        String liveSessionId,
        String groupId,
        String toUserId,
        String currency,
        Long amount,
        String payPin,
        String clientOrderId,
        String memo,
        Long liveTipOrderId
    ) {}

    @PostMapping("/live-tip")
    public Map<String, Object> liveTip(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody(required = false) LiveTipRequest body
    ) {
        authService.verify(headerToken, sign, requestTime);
        String fromUserId = requireUserId(authorization);
        return tipService.tip(fromUserId, body);
    }

    private String requireUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            String token = authorization.substring(7);
            String userId = jwtService.parseUserId(token);
            if (userId == null || userId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
            }
            User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
            if (user.getStatus() != 1) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
            }
            var jti = jwtService.parseJti(token);
            if (jti.isPresent() && !sessionService.isSessionActive(userId, jti.get())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED");
            }
            return userId;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
