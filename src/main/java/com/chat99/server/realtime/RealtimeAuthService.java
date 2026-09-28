package com.chat99.server.realtime;

import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.security.UserAuthStatusService;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RealtimeAuthService {

    public record AuthResult(String userId, String deviceId) {}

    private final JwtService jwtService;
    private final UserSessionService sessionService;
    private final UserAuthStatusService userAuthStatusService;

    public RealtimeAuthService(JwtService jwtService,
                               UserSessionService sessionService,
                               UserAuthStatusService userAuthStatusService) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.userAuthStatusService = userAuthStatusService;
    }

    public Optional<AuthResult> authenticate(String token, String deviceId) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String userId = jwtService.parseUserId(token);
            Optional<String> jti = jwtService.parseJti(token);
            if (jti.isEmpty() || !sessionService.isSessionActive(userId, jti.get())) {
                return Optional.empty();
            }
            UserAuthStatusService.Result user = userAuthStatusService.resolve(userId);
            if (!user.found() || user.status() != 1) {
                return Optional.empty();
            }
            String resolvedDeviceId = deviceId;
            if (resolvedDeviceId == null || resolvedDeviceId.isBlank()) {
                resolvedDeviceId = jwtService.parseDeviceId(token).orElse("");
            }
            return Optional.of(new AuthResult(userId, resolvedDeviceId));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }
}
