package com.chat99.server.adminapi;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.WalletSweepLog;
import com.chat99.server.wallet.WalletSweepLogRepository;
import com.chat99.server.wallet.WalletSweepStatus;
import com.chat99.server.wallet.WalletSweepTrigger;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AdminWalletSweepLogService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final WalletSweepLogRepository sweepLogRepository;
    private final UserRepository userRepository;

    public AdminWalletSweepLogService(WalletSweepLogRepository sweepLogRepository,
                                      UserRepository userRepository) {
        this.sweepLogRepository = sweepLogRepository;
        this.userRepository = userRepository;
    }

    public SweepLogListResponse list(Authentication auth,
                                       String keyword,
                                       String status,
                                       String trigger,
                                       String createdFrom,
                                       String createdTo,
                                       int page,
                                       int pageSize) {
        AdminAccess.requirePermission(auth, "wallet.read");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Instant from = parseDateStart(createdFrom);
        Instant to = parseDateEnd(createdTo);
        WalletSweepStatus statusEnum = parseStatus(status);
        WalletSweepTrigger triggerEnum = parseTrigger(trigger);

        Page<WalletSweepLog> result = sweepLogRepository.findAll(
            buildSpec(blankToNull(keyword), statusEnum, triggerEnum, from, to),
            PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        Set<String> userIds = new HashSet<>();
        result.getContent().forEach(row -> userIds.add(row.getUserId()));
        Map<String, String> nicknames = loadNicknames(userIds);

        List<SweepLogItem> items = result.getContent().stream()
            .map(row -> toItem(row, nicknames))
            .toList();

        return new SweepLogListResponse(
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext());
    }

    private SweepLogItem toItem(WalletSweepLog row, Map<String, String> nicknames) {
        return new SweepLogItem(
            row.getId(),
            row.getUserId(),
            nicknames.getOrDefault(row.getUserId(), "—"),
            row.getFromAddress(),
            row.getHotWalletAddress(),
            AdminUserFormats.decimalFromMicro(row.getUsdtSweptMicro()),
            AdminUserFormats.decimalFromTrxSun(row.getTrxSweptSun()),
            row.getUsdtTxId(),
            row.getTrxTxId(),
            row.getStatus() != null ? row.getStatus().name().toLowerCase(Locale.ROOT) : null,
            row.getTriggerType() != null ? row.getTriggerType().name().toLowerCase(Locale.ROOT) : null,
            row.getOperator(),
            row.getFailReason(),
            row.getCreatedAt() != null ? row.getCreatedAt().toString() : null);
    }

    private Specification<WalletSweepLog> buildSpec(String keyword,
                                                    WalletSweepStatus status,
                                                    WalletSweepTrigger trigger,
                                                    Instant from,
                                                    Instant to) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                List<Predicate> kwPreds = new ArrayList<>();
                kwPreds.add(cb.like(cb.lower(root.get("userId")), like));
                kwPreds.add(cb.like(cb.lower(root.get("fromAddress")), like));
                kwPreds.add(cb.like(cb.lower(root.get("usdtTxId")), like));
                kwPreds.add(cb.like(cb.lower(root.get("trxTxId")), like));
                kwPreds.add(cb.like(cb.lower(root.get("operator")), like));
                List<User> users = userRepository.findAll((r, q, c) ->
                    c.like(c.lower(r.get("nickname")), like));
                if (!users.isEmpty()) {
                    kwPreds.add(root.get("userId").in(users.stream().map(User::getUserId).toList()));
                }
                preds.add(cb.or(kwPreds.toArray(Predicate[]::new)));
            }
            if (status != null) {
                preds.add(cb.equal(root.get("status"), status));
            }
            if (trigger != null) {
                preds.add(cb.equal(root.get("triggerType"), trigger));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThan(root.get("createdAt"), to));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private Map<String, String> loadNicknames(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        userRepository.findByUserIdIn(userIds).forEach(u -> map.put(u.getUserId(), u.getNickname()));
        return map;
    }

    private static WalletSweepStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WalletSweepStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static WalletSweepTrigger parseTrigger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WalletSweepTrigger.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseDateStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE).atStartOfDay(ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant parseDateEnd(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE).plusDays(1).atStartOfDay(ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SweepLogListResponse(
        List<SweepLogItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SweepLogItem(
        long id,
        String userUid,
        String nickname,
        String fromAddress,
        String hotWalletAddress,
        String usdtSwept,
        String trxSwept,
        String usdtTxId,
        String trxTxId,
        String status,
        String triggerType,
        String operator,
        String failReason,
        String createdAt) {}
}
