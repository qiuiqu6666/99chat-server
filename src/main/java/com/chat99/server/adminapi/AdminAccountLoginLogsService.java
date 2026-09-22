package com.chat99.server.adminapi;

import com.chat99.server.common.ClientContext;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminAccountLoginLogsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AdminLoginLogRepository loginLogRepository;
    private final AdminAccountRepository accountRepository;
    private final ClientContext clientContext;

    public AdminAccountLoginLogsService(AdminLoginLogRepository loginLogRepository,
                                        AdminAccountRepository accountRepository,
                                        ClientContext clientContext) {
        this.loginLogRepository = loginLogRepository;
        this.accountRepository = accountRepository;
        this.clientContext = clientContext;
    }

    public void recordAttempt(HttpServletRequest http, Long adminUserId, String usernameAttempted,
                              boolean success, String failReason) {
        AdminLoginLog row = new AdminLoginLog();
        row.setAdminUserId(adminUserId);
        row.setUsernameAttempted(usernameAttempted == null ? "" : usernameAttempted.trim());
        row.setSuccess(success);
        row.setFailReason(blankToNull(failReason));
        if (http != null) {
            row.setIp(clientContext.ip(http));
            row.setUserAgent(clientContext.userAgent(http));
        }
        loginLogRepository.save(row);
    }

    public LoginLogsResponse list(String adminUserId, String username, String success,
                                  String ip, String loginAtFrom, String loginAtTo,
                                  int page, int pageSize, String sort) {
        Instant from = parseDateStart(loginAtFrom);
        Instant to = parseDateEnd(loginAtTo);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);

        Specification<AdminLoginLog> spec = buildSpec(
            parseAdminUserId(adminUserId), blankToNull(username), parseSuccess(success),
            blankToNull(ip), from, to);
        Page<AdminLoginLog> result = loginLogRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveSort(sort)));

        Map<Long, AdminAccount> accounts = loadAccounts(result.getContent());
        List<LoginLogItem> items = result.getContent().stream()
            .map(row -> toItem(row, accounts.get(row.getAdminUserId())))
            .toList();
        return new LoginLogsResponse(
            "admin_login_logs",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext());
    }

    private Map<Long, AdminAccount> loadAccounts(List<AdminLoginLog> rows) {
        Set<Long> ids = rows.stream()
            .map(AdminLoginLog::getAdminUserId)
            .filter(id -> id != null && id > 0)
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, AdminAccount> out = new HashMap<>();
        for (AdminAccount account : accountRepository.findAllById(ids)) {
            out.put(account.getId(), account);
        }
        return out;
    }

    private LoginLogItem toItem(AdminLoginLog row, AdminAccount account) {
        return new LoginLogItem(
            row.getId(),
            row.getAdminUserId() == null ? null : String.valueOf(row.getAdminUserId()),
            row.getUsernameAttempted(),
            row.isSuccess() ? 1 : 0,
            row.getIp(),
            null,
            row.getUserAgent(),
            row.getFailReason(),
            row.getLoginAt() == null ? null : row.getLoginAt().getEpochSecond(),
            account == null ? null : account.getUsername(),
            account == null ? null : account.getDisplayName());
    }

    private Specification<AdminLoginLog> buildSpec(Long adminUserId, String username, Boolean success,
                                                     String ip, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (adminUserId != null) {
                preds.add(cb.equal(root.get("adminUserId"), adminUserId));
            }
            if (username != null) {
                preds.add(cb.like(cb.lower(root.get("usernameAttempted")),
                    "%" + username.toLowerCase(Locale.ROOT) + "%"));
            }
            if (success != null) {
                preds.add(success ? cb.isTrue(root.get("success")) : cb.isFalse(root.get("success")));
            }
            if (ip != null) {
                preds.add(cb.equal(root.get("ip"), ip));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("loginAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("loginAt"), to));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "loginAt");
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "login_at_asc" -> Sort.by(Sort.Direction.ASC, "loginAt");
            case "id_desc" -> Sort.by(Sort.Direction.DESC, "id");
            case "id_asc" -> Sort.by(Sort.Direction.ASC, "id");
            default -> Sort.by(Sort.Direction.DESC, "loginAt");
        };
    }

    private static Long parseAdminUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "invalid admin_user_id");
        }
    }

    private static Boolean parseSuccess(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim()) {
            case "1", "true" -> true;
            case "0", "false" -> false;
            default -> throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "invalid success filter");
        };
    }

    private static Instant parseDateStart(String value) {
        LocalDate date = parseDate(value);
        return date == null ? null : date.atStartOfDay(ZONE).toInstant();
    }

    private static Instant parseDateEnd(String value) {
        LocalDate date = parseDate(value);
        return date == null ? null : date.plusDays(1).atStartOfDay(ZONE).minusNanos(1).toInstant();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DATE);
        } catch (DateTimeParseException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "invalid date format, use YYYY-MM-DD");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginLogItem(
        long id,
        String adminUserId,
        String usernameAttempted,
        int success,
        String ip,
        String geoAddress,
        String userAgent,
        String failReason,
        Long loginAt,
        String adminUsername,
        String adminDisplayName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginLogsResponse(
        String source,
        List<LoginLogItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}
}
