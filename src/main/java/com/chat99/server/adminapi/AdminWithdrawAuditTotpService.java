package com.chat99.server.adminapi;

import com.chat99.server.common.AppSettingService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminWithdrawAuditTotpService {

    public static final String SECRET_KEY = "ADMIN_WITHDRAW_AUDIT_TOTP_SECRET";
    public static final String PENDING_KEY = "ADMIN_WITHDRAW_AUDIT_TOTP_PENDING";

    private static final String ISSUER = "99chat";
    private static final String ACCOUNT = "WithdrawAudit";

    private final AppSettingService settings;

    public AdminWithdrawAuditTotpService(AppSettingService settings) {
        this.settings = settings;
    }

    public TotpStatusResponse status() {
        return new TotpStatusResponse(isConfigured());
    }

    @Transactional
    public TotpSetupResponse beginSetup(Authentication auth) {
        AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        String pending = settings.get(PENDING_KEY).filter(s -> !s.isBlank()).orElse(null);
        if (pending != null) {
            return new TotpSetupResponse(
                TotpUtil.otpAuthUri(pending, ISSUER, ACCOUNT),
                pending);
        }
        String secret = TotpUtil.generateSecret();
        settings.set(PENDING_KEY, secret);
        return new TotpSetupResponse(
            TotpUtil.otpAuthUri(secret, ISSUER, ACCOUNT),
            secret);
    }

    @Transactional
    public TotpStatusResponse confirmSetup(Authentication auth, String totpCode) {
        AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        if (totpCode == null || totpCode.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "totp_required", "请填写谷歌验证码");
        }
        String pending = settings.get(PENDING_KEY).orElse(null);
        if (pending == null || pending.isBlank()) {
            throw new AdminApiException(HttpStatus.CONFLICT, "totp_setup_not_started", "totp_setup_not_started");
        }
        if (!TotpUtil.verify(pending.trim(), totpCode.trim())) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "totp_invalid", "谷歌验证码错误");
        }
        settings.set(SECRET_KEY, pending);
        settings.set(PENDING_KEY, "");
        return new TotpStatusResponse(true);
    }

    public void requireValid(String totpCode) {
        if (totpCode == null || totpCode.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "totp_required", "请填写谷歌验证码");
        }
        if (!isConfigured()) {
            throw new AdminApiException(HttpStatus.PRECONDITION_FAILED, "totp_not_configured", "请先设置提现审核谷歌验证");
        }
        String secret = settings.get(SECRET_KEY).orElse("").trim();
        if (!TotpUtil.verify(secret, totpCode.trim())) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "totp_invalid", "谷歌验证码错误");
        }
    }

    public boolean isConfigured() {
        return settings.get(SECRET_KEY).filter(s -> !s.isBlank()).isPresent();
    }

    @Transactional
    public TotpStatusResponse reset(Authentication auth, String totpCode) {
        AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        requireValid(totpCode);
        settings.set(SECRET_KEY, "");
        settings.set(PENDING_KEY, "");
        return new TotpStatusResponse(false);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TotpStatusResponse(boolean configured) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TotpSetupResponse(String otpauthUri, String secret) {}
}
