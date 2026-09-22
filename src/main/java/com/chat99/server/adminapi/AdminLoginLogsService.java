package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.chat99.server.user.DeviceModelDisplayService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import com.chat99.server.user.UserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
public class AdminLoginLogsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final LoginLogRepository loginLogRepository;
    private final UserRepository userRepository;
    private final UserDeviceRepository deviceRepository;
    private final DeviceModelDisplayService modelDisplay;

    public AdminLoginLogsService(LoginLogRepository loginLogRepository,
                                 UserRepository userRepository,
                                 UserDeviceRepository deviceRepository,
                                 DeviceModelDisplayService modelDisplay) {
        this.loginLogRepository = loginLogRepository;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.modelDisplay = modelDisplay;
    }

    public LoginLogsResponse list(String userUid, String loginIp, Integer deviceType,
                                  String loginTimeFrom, String loginTimeTo,
                                  int page, int pageSize, String sort) {
        Instant from = parseDateStart(loginTimeFrom);
        Instant to = parseDateEnd(loginTimeTo);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        String uid = blankToNull(userUid);
        String ip = blankToNull(loginIp);

        Specification<LoginLog> spec = buildSpec(uid, ip, deviceType, from, to);
        Page<LoginLog> result = loginLogRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveSort(sort)));

        Map<String, String> nicknames = loadNicknames(result.getContent());
        Map<String, String> deviceModels = loadDeviceModels(result.getContent());
        List<LoginLogItem> items = result.getContent().stream()
            .map(log -> toItem(log, nicknames.get(log.getUserId()), deviceModels.get(deviceModelKey(log))))
            .toList();
        return new LoginLogsResponse(
            "login_log",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext());
    }

    private Specification<LoginLog> buildSpec(String userUid, String loginIp, Integer deviceType,
                                                Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            if (loginIp != null) {
                preds.add(cb.equal(root.get("ip"), loginIp));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            Predicate devicePred = deviceTypePredicate(root, cb, deviceType);
            if (devicePred != null) {
                preds.add(devicePred);
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static Predicate deviceTypePredicate(Root<LoginLog> root, CriteriaBuilder cb,
                                                 Integer deviceType) {
        if (deviceType == null) {
            return null;
        }
        var platform = cb.lower(cb.coalesce(root.get("clientPlatform"), ""));
        return switch (deviceType) {
            case 0 -> cb.like(platform, "%android%");
            case 1 -> cb.like(platform, "%ios%");
            case 2 -> cb.or(
                cb.like(platform, "%web%"),
                cb.like(platform, "%windows%"),
                cb.like(platform, "%macos%"),
                cb.like(platform, "%linux%"));
            case -1 -> cb.or(
                cb.isNull(root.get("clientPlatform")),
                cb.equal(root.get("clientPlatform"), ""),
                cb.and(
                    cb.not(cb.like(platform, "%android%")),
                    cb.not(cb.like(platform, "%ios%")),
                    cb.not(cb.like(platform, "%web%")),
                    cb.not(cb.like(platform, "%windows%")),
                    cb.not(cb.like(platform, "%macos%")),
                    cb.not(cb.like(platform, "%linux%"))));
            default -> null;
        };
    }

    private Map<String, String> loadNicknames(List<LoginLog> logs) {
        Set<String> ids = logs.stream()
            .map(LoginLog::getUserId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (User user : userRepository.findByUserIdIn(ids)) {
            out.put(user.getUserId(), user.getNickname());
        }
        return out;
    }

    private LoginLogItem toItem(LoginLog log, String nickname, String deviceModel) {
        return new LoginLogItem(
            log.getId(),
            log.getUserId(),
            nickname,
            AdminUserFormats.mapDeviceType(log.getClientPlatform()),
            buildDeviceInfo(log),
            log.getIp(),
            log.getCreatedAt() == null ? null : log.getCreatedAt().getEpochSecond(),
            AdminUserFormats.formatTime(log.getCreatedAt()),
            log.getDeviceId(),
            maskToken(log.getDeviceId()),
            deviceModel,
            log.getClientVersion() == null ? "" : log.getClientVersion().trim(),
            log.isSuccess()
                ? "成功"
                : (log.getFailReason() != null && !log.getFailReason().isBlank()
                    ? log.getFailReason() : "失败"),
            0);
    }

    private Map<String, String> loadDeviceModels(List<LoginLog> logs) {
        Map<String, String> out = new HashMap<>();
        for (LoginLog log : logs) {
            String key = deviceModelKey(log);
            if (key == null || out.containsKey(key)) {
                continue;
            }
            deviceRepository.findByUserIdAndDeviceId(log.getUserId(), log.getDeviceId())
                .map(UserDevice::getModel)
                .filter(model -> model != null && !model.isBlank())
                .ifPresent(model -> out.put(key, modelDisplay.display(
                    log.getClientPlatform(), model)));
        }
        return out;
    }

    private static String deviceModelKey(LoginLog log) {
        if (log.getUserId() == null || log.getUserId().isBlank()
            || log.getDeviceId() == null || log.getDeviceId().isBlank()) {
            return null;
        }
        return log.getUserId() + ":" + log.getDeviceId();
    }

    private static String buildDeviceInfo(LoginLog log) {
        if (log.getUserAgent() != null && !log.getUserAgent().isBlank()) {
            return log.getUserAgent().trim();
        }
        List<String> parts = new ArrayList<>();
        if (log.getClientPlatform() != null && !log.getClientPlatform().isBlank()) {
            parts.add(log.getClientPlatform().trim());
        }
        if (log.getClientVersion() != null && !log.getClientVersion().isBlank()) {
            parts.add(log.getClientVersion().trim());
        }
        if (log.getLoginType() != null && !log.getLoginType().isBlank()) {
            parts.add(log.getLoginType().trim());
        }
        return parts.isEmpty() ? "" : String.join(" ", parts);
    }

    private static String maskToken(String value) {
        if (value == null || value.length() < 8) {
            return value;
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private static Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "login_time_asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "history_id_desc" -> Sort.by(Sort.Direction.DESC, "id");
            case "history_id_asc" -> Sort.by(Sort.Direction.ASC, "id");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    private static Instant parseDateStart(String value) {
        LocalDate date = parseDate(value);
        if (date == null) {
            return null;
        }
        return date.atStartOfDay(ZONE).toInstant();
    }

    private static Instant parseDateEnd(String value) {
        LocalDate date = parseDate(value);
        if (date == null) {
            return null;
        }
        return date.plusDays(1).atStartOfDay(ZONE).minusNanos(1).toInstant();
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
        long historyId,
        String userUid,
        String userNickname,
        Integer deviceType,
        String deviceInfo,
        String loginIp,
        Long loginTime,
        String loginTime2,
        String hardwareId,
        String deviceTokenMasked,
        String deviceModel,
        String clientVersion,
        String status,
        Integer isCurrent) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginLogsResponse(
        String source,
        List<LoginLogItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}
}
