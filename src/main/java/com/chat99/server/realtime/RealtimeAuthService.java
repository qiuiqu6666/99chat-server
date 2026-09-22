package com.chat99.server.realtime;

import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RealtimeAuthService {

    public record AuthResult(String userId, String deviceId) {}

    private final JwtService jwtService;
    private final UserSessionService sessionService;
    private final UserRepository userRepository;

    public RealtimeAuthService(JwtService jwtService,
                               UserSessionService sessionService,
                               UserRepository userRepository) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.userRepository = userRepository;
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
            User user = userRepository.findByUserId(userId).orElse(null);
            if (user == null || user.getStatus() != 1) {
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
