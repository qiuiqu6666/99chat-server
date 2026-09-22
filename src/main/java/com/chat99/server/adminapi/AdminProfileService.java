package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminProfileService {

    private final AdminAccountRepository accountRepository;
    private final AdminLoginLogRepository loginLogRepository;

    public AdminProfileService(AdminAccountRepository accountRepository,
                               AdminLoginLogRepository loginLogRepository) {
        this.accountRepository = accountRepository;
        this.loginLogRepository = loginLogRepository;
    }

    public AdminAccount requireAccount(AdminPrincipal principal) {
        return accountRepository.findByUsername(principal.username())
            .filter(AdminAccount::isEnabled)
            .orElseThrow(() -> new AdminApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "invalid_token"));
    }

    public ProfileView getProfile(AdminPrincipal principal) {
        return toView(requireAccount(principal));
    }

    @Transactional
    public ProfileView updateProfile(AdminPrincipal principal, UpdateProfileRequest req) {
        AdminAccount account = requireAccount(principal);
        if (req.nickname() != null) {
            account.setNickname(trimOrNull(req.nickname(), 32));
        }
        if (req.displayName() != null) {
            account.setDisplayName(trimOrNull(req.displayName(), 64));
        }
        if (req.email() != null) {
            account.setEmail(trimOrNull(req.email(), 128));
        }
        if (req.phone() != null) {
            account.setPhone(trimOrNull(req.phone(), 32));
        }
        if (req.avatar() != null) {
            account.setAvatar(trimOrNull(req.avatar(), 512));
        }
        accountRepository.save(account);
        return toView(account);
    }

    private ProfileView toView(AdminAccount account) {
        List<String> permissions = parsePermissions(account.getPermissionsCsv());
        var lastLogin = loginLogRepository
            .findTopByAdminUserIdAndSuccessTrueOrderByLoginAtDesc(account.getId())
            .orElse(null);
        return new ProfileView(
            account.getId(),
            account.getUsername(),
            account.getNickname(),
            account.getDisplayName(),
            resolveRole(permissions),
            permissions,
            account.getEmail(),
            account.getPhone(),
            account.getAvatar(),
            lastLogin == null ? null : AdminUserFormats.formatTime(lastLogin.getLoginAt()),
            lastLogin == null ? null : lastLogin.getIp(),
            AdminUserFormats.formatTime(account.getCreatedAt()),
            account.isEnabled() ? "normal" : "disabled");
    }

    static List<String> parsePermissions(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    static String resolveRole(List<String> permissions) {
        if (permissions != null && permissions.contains("admin.manage")) {
            return "super_admin";
        }
        return "admin";
    }

    private static String trimOrNull(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLen) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "field too long, max " + maxLen);
        }
        return trimmed;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProfileView(
        long id,
        String username,
        String nickname,
        String displayName,
        String role,
        List<String> permissions,
        String email,
        String phone,
        String avatar,
        String lastLoginTime,
        String lastLoginIp,
        String createdAt,
        String status) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateProfileRequest(
        String nickname,
        String displayName,
        String email,
        String phone,
        String avatar) {}
}
