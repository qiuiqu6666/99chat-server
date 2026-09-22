/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminAccess;
import com.chat99.server.adminapi.AdminAccount;
import com.chat99.server.adminapi.AdminAccountLoginLogsService;
import com.chat99.server.adminapi.AdminAuthController;
import com.chat99.server.adminapi.AdminAuthService;
import com.chat99.server.adminapi.AdminJwtService;
import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.adminapi.AdminProfileService;
import com.chat99.server.adminapi.AdminSessionService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/v1/auth"})
@JsonNaming(value=PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminAuthController {
    private final AdminAuthService authService;
    private final AdminAccountLoginLogsService loginLogsService;
    private final AdminProfileService profileService;
    private final AdminJwtService jwtService;
    private final AdminSessionService sessionService;

    public AdminAuthController(AdminAuthService authService, AdminAccountLoginLogsService loginLogsService, AdminProfileService profileService, AdminJwtService jwtService, AdminSessionService sessionService) {
        this.authService = authService;
        this.loginLogsService = loginLogsService;
        this.profileService = profileService;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
    }

    @PostMapping(value={"/login"})
    public LoginResponse login(HttpServletRequest http, @RequestBody LoginRequest req) {
        AdminAuthService.LoginResult r = this.authService.login(http, req.username(), req.password());
        return new LoginResponse(r.accessToken(), r.expiresIn(),
            new LoginUserView(r.user().username(), new java.util.HashSet<>(r.user().permissions())));
    }

    @GetMapping(value={"/me"})
    public MeResponse me(Authentication auth) {
        AdminPrincipal principal = AdminAccess.require((Authentication)auth);
        return new MeResponse(this.authService.me(principal));
    }

    @GetMapping(value={"/me/login-logs"})
    public AdminAccountLoginLogsService.LoginLogsResponse myLoginLogs(Authentication auth, @RequestParam(defaultValue="1") int page, @RequestParam(name="page_size", defaultValue="10") int pageSize) {
        AdminPrincipal principal = AdminAccess.require((Authentication)auth);
        AdminAccount account = this.profileService.requireAccount(principal);
        return this.loginLogsService.list(String.valueOf(account.getId()), null, null, null, null, null, page, pageSize, "login_at_desc");
    }

    @PostMapping(value={"/change-password"})
    public Map<String, String> changePassword(HttpServletRequest http, Authentication auth, @RequestBody ChangePasswordRequest req) {
        AdminPrincipal principal = AdminAccess.require((Authentication)auth);
        this.authService.changePassword(http, principal, req.oldPassword(), req.newPassword());
        return Map.of("message", "ok");
    }

    @PostMapping(value={"/logout"})
    public Map<String, String> logout(HttpServletRequest http, Authentication auth) {
        AdminPrincipal principal = AdminAccess.require((Authentication)auth);
        String header = http.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AdminJwtService.AdminTokenClaims claims = this.jwtService.parse(header.substring(7).trim());
            this.sessionService.revoke(principal.username(), claims.jti());
        }
        return Map.of("message", "ok");
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginUserView(String username, java.util.Set<String> permissions) {}

    public record LoginResponse(String accessToken, long expiresIn, LoginUserView user) {}

    public record MeResponse(AdminProfileService.ProfileView user) {}

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}
}
