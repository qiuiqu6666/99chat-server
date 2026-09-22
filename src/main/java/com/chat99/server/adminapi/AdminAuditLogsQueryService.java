package com.chat99.server.adminapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditLogsQueryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AdminAuditLogRepository auditLogRepository;
    private final AdminAccountRepository accountRepository;
    private final ObjectMapper json = new ObjectMapper();

    public AdminAuditLogsQueryService(AdminAuditLogRepository auditLogRepository,
                                      AdminAccountRepository accountRepository) {
        this.auditLogRepository = auditLogRepository;
        this.accountRepository = accountRepository;
    }

    public AuditLogsResponse list(String adminUserId, String action, String resourceType,
                                  String resourceId, String createdFrom, String createdTo,
                                  int page, int pageSize, String sort) {
        Instant from = parseDateStart(createdFrom);
        Instant to = parseDateEnd(createdTo);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);

        String adminUsername = resolveAdminUsername(adminUserId);
        Specification<AdminAuditLog> spec = buildSpec(
            adminUsername, blankToNull(action), blankToNull(resourceType), blankToNull(resourceId), from, to);
        Page<AdminAuditLog> result = auditLogRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveSort(sort)));

        Map<String, AdminAccount> accountsByUsername = loadAccountsByUsername(result.getContent());
        List<AuditLogItem> items = result.getContent().stream()
            .map(row -> toItem(row, accountsByUsername.get(row.getAdminUsername())))
            .toList();
        return new AuditLogsResponse(
            "admin_audit_logs",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext());
    }

    private String resolveAdminUsername(String adminUserIdRaw) {
        if (adminUserIdRaw == null || adminUserIdRaw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(adminUserIdRaw.trim());
            return accountRepository.findById(id)
                .map(AdminAccount::getUsername)
                .orElse("__missing_admin__");
        } catch (NumberFormatException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "invalid admin_user_id");
        }
    }

    private Map<String, AdminAccount> loadAccountsByUsername(List<AdminAuditLog> rows) {
        Set<String> usernames = rows.stream()
            .map(AdminAuditLog::getAdminUsername)
            .filter(u -> u != null && !u.isBlank())
            .collect(Collectors.toSet());
        if (usernames.isEmpty()) {
            return Map.of();
        }
        Map<String, AdminAccount> out = new HashMap<>();
        for (AdminAccount account : accountRepository.findByUsernameIn(usernames)) {
            out.put(account.getUsername(), account);
        }
        return out;
    }

    private AuditLogItem toItem(AdminAuditLog row, AdminAccount account) {
        Map<String, Object> detail = parseDetail(row.getDetailJson());
        String resourceType = deriveResourceType(row.getAction(), row.getTargetUserId());
        String resourceId = deriveResourceId(row.getTargetUserId(), detail);
        return new AuditLogItem(
            row.getId(),
            account == null ? null : String.valueOf(account.getId()),
            row.getAction(),
            resourceType,
            resourceId,
            detail == null || detail.isEmpty() ? null : detail,
            row.getIp(),
            null,
            row.getCreatedAt() == null ? null : row.getCreatedAt().getEpochSecond(),
            row.getAdminUsername(),
            null);
    }

    private Map<String, Object> parseDetail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String deriveResourceType(String action, String targetUserId) {
        if (targetUserId != null && !targetUserId.isBlank()) {
            return "user";
        }
        if (action == null || action.isBlank()) {
            return null;
        }
        int dot = action.indexOf('.');
        return dot > 0 ? action.substring(0, dot) : action;
    }

    private static String deriveResourceId(String targetUserId, Map<String, Object> detail) {
        if (targetUserId != null && !targetUserId.isBlank()) {
            return targetUserId;
        }
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        for (String key : List.of("id", "feedbackId", "groupId", "deviceId")) {
            Object value = detail.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private Specification<AdminAuditLog> buildSpec(String adminUsername, String action,
                                                   String resourceType, String resourceId,
                                                   Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (adminUsername != null) {
                preds.add(cb.equal(root.get("adminUsername"), adminUsername));
            }
            if (action != null) {
                preds.add(cb.equal(root.get("action"), action));
            }
            if (resourceType != null) {
                if ("user".equalsIgnoreCase(resourceType)) {
                    preds.add(cb.and(
                        cb.isNotNull(root.get("targetUserId")),
                        cb.notEqual(root.get("targetUserId"), "")));
                } else {
                    preds.add(cb.like(root.get("action"), resourceType + ".%"));
                }
            }
            if (resourceId != null) {
                preds.add(cb.or(
                    cb.equal(root.get("targetUserId"), resourceId),
                    cb.like(root.get("detailJson"), "%\"" + escapeLike(resourceId) + "\"%")));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "created_at_asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "id_desc" -> Sort.by(Sort.Direction.DESC, "id");
            case "id_asc" -> Sort.by(Sort.Direction.ASC, "id");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
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
    public record AuditLogItem(
        long id,
        String adminUserId,
        String action,
        String resourceType,
        String resourceId,
        Object detail,
        String ip,
        String geoAddress,
        Long createdAt,
        String adminUsername,
        String adminDisplayName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AuditLogsResponse(
        String source,
        List<AuditLogItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}
}
