/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminAccount;
import com.chat99.server.adminapi.AdminAccountLoginLogsService;
import com.chat99.server.adminapi.AdminAccountRepository;
import com.chat99.server.adminapi.AdminApiException;
import com.chat99.server.adminapi.AdminAuditService;
import com.chat99.server.adminapi.AdminAuthService;
import com.chat99.server.adminapi.AdminJwtService;
import com.chat99.server.adminapi.AdminLoginRateLimiter;
import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.adminapi.AdminProfileService;
import com.chat99.server.adminapi.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthService {
    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AdminJwtService jwtService;
    private final AdminSessionService sessionService;
    private final AdminLoginRateLimiter loginRateLimiter;
    private final AdminAccountLoginLogsService loginLogsService;
    private final AdminProfileService profileService;
    private final AdminAuditService auditService;

    public AdminAuthService(AdminAccountRepository repository, PasswordEncoder passwordEncoder, AdminJwtService jwtService, AdminSessionService sessionService, AdminLoginRateLimiter loginRateLimiter, AdminAccountLoginLogsService loginLogsService, AdminProfileService profileService, AdminAuditService auditService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.loginRateLimiter = loginRateLimiter;
        this.loginLogsService = loginLogsService;
        this.profileService = profileService;
        this.auditService = auditService;
    }

    public LoginResult login(HttpServletRequest http, String username, String password) {
        String attempted = username == null ? "" : username.trim();
        this.loginRateLimiter.check(http, attempted);
        AdminAccount account = this.repository.findByUsername(attempted).orElse(null);
        if (account == null) {
            this.loginRateLimiter.recordFailure(http, attempted);
            this.loginLogsService.recordAttempt(http, null, attempted, false, "invalid_credentials");
            throw new AdminApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "invalid credentials");
        }
        if (!account.isEnabled() || !this.passwordEncoder.matches((CharSequence)password, account.getPasswordHash())) {
            this.loginRateLimiter.recordFailure(http, attempted);
            this.loginLogsService.recordAttempt(http, account.getId(), attempted, false, "invalid_credentials");
            throw new AdminApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "invalid credentials");
        }
        this.loginRateLimiter.clear(attempted);
        this.loginLogsService.recordAttempt(http, account.getId(), attempted, true, null);
        List<String> permissions = Arrays.stream(account.getPermissionsCsv().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        String token = this.sessionService.issue(account.getUsername(), permissions);
        return new LoginResult(token, this.jwtService.expireSeconds(), new LoginUser(account.getUsername(), permissions));
    }

    public AdminProfileService.ProfileView me(AdminPrincipal principal) {
        return this.profileService.getProfile(principal);
    }

    @Transactional
    public void changePassword(HttpServletRequest http, AdminPrincipal principal, String oldPassword, String newPassword) {
        AdminAccount account = this.profileService.requireAccount(principal);
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "old_password required");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "new_password must be at least 8 characters");
        }
        if (!this.passwordEncoder.matches((CharSequence)oldPassword, account.getPasswordHash())) {
            throw new AdminApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "invalid old password");
        }
        account.setPasswordHash(this.passwordEncoder.encode((CharSequence)newPassword));
        this.repository.save(account);
        this.sessionService.revokeAll(account.getUsername());
        this.auditService.log(http, account.getUsername(), "admin.password.change", String.valueOf(account.getId()), Map.of("username", account.getUsername()));
    }




    public record LoginUser(String username, java.util.List<String> permissions) {}

    public record LoginResult(String accessToken, long expiresIn, LoginUser user) {}
}
