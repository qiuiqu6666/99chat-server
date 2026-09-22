package com.chat99.server.adminapi;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.sync.UserContactItem;
import com.chat99.server.sync.UserContactItemRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
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
import org.springframework.stereotype.Service;

@Service
public class AdminContactBookService {

    private static final int ACTIVE = 1;

    private final UserContactItemRepository contactRepository;
    private final UserRepository userRepository;
    private final PhoneUtils phoneUtils;
    private final ObjectMapper json = new ObjectMapper();

    public AdminContactBookService(UserContactItemRepository contactRepository,
                                   UserRepository userRepository,
                                   PhoneUtils phoneUtils) {
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.phoneUtils = phoneUtils;
    }

    public ContactBookListResponse list(String userUid, String keyword, String contactName,
                                        String contactPhone, String hitPlatformUser,
                                        int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        String uid = blankToNull(userUid);
        String kw = blankToNull(keyword);
        String name = blankToNull(contactName);
        String phone = blankToNull(contactPhone);
        String hit = blankToNull(hitPlatformUser);
        Sort order = resolveSort(sort);

        Specification<UserContactItem> spec = buildSpec(uid, kw, name, phone, hit);
        Page<UserContactItem> result = contactRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, order));
        List<ContactBookItem> items = mapItems(result.getContent());
        return new ContactBookListResponse(
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext());
    }

    private List<ContactBookItem> mapItems(List<UserContactItem> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> nicknames = loadNicknames(rows);
        List<ContactBookItem> items = new ArrayList<>(rows.size());
        for (UserContactItem row : rows) {
            List<String> phones = parsePhones(row.getPhonesJson());
            String primaryPhone = phones.isEmpty() ? null : phones.get(0);
            items.add(new ContactBookItem(
                String.valueOf(row.getId()),
                row.getUserId(),
                nicknames.get(row.getUserId()),
                row.getDisplayName(),
                primaryPhone,
                maskPhone(primaryPhone),
                null,
                "客户端同步",
                row.isPlatformUser(),
                row.getMatchedUserId(),
                epoch(row.getCreatedAt()),
                epoch(row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getSyncedAt())));
        }
        return items;
    }

    private Map<String, String> loadNicknames(List<UserContactItem> rows) {
        Set<String> ids = new HashSet<>();
        for (UserContactItem row : rows) {
            if (row.getUserId() != null && !row.getUserId().isBlank()) {
                ids.add(row.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (User user : userRepository.findByUserIdIn(ids)) {
            out.put(user.getUserId(), user.getNickname());
        }
        return out;
    }

    private List<String> parsePhones(String phonesJson) {
        if (phonesJson == null || phonesJson.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(phonesJson,
                json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String normalizePhoneKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("+")) {
                return phoneUtils.parseE164(trimmed).e164();
            }
            if (trimmed.matches("^[0-9]+$")) {
                return phoneUtils.parseWithRegion(trimmed, "CN").e164();
            }
        } catch (IllegalArgumentException ignored) {
            return trimmed;
        }
        return trimmed;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String e164 = normalizePhoneKey(phone);
        if (e164 != null && e164.startsWith("+")) {
            return phoneUtils.mask(e164);
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return phone;
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    private Specification<UserContactItem> buildSpec(
        String userUid, String keyword, String contactName, String contactPhone,
        String hitPlatformUser) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("status"), ACTIVE));
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            if (contactName != null) {
                preds.add(cb.like(cb.lower(root.get("displayName")),
                    "%" + contactName.toLowerCase(Locale.ROOT) + "%"));
            }
            if (contactPhone != null) {
                preds.add(cb.like(root.get("phonesJson"), "%" + contactPhone + "%"));
            }
            if (hitPlatformUser != null) {
                boolean registered = "1".equals(hitPlatformUser)
                    || "true".equalsIgnoreCase(hitPlatformUser);
                preds.add(cb.equal(root.get("platformUser"), registered));
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("displayName"), "")), like),
                    cb.like(cb.lower(root.get("phonesJson")), like),
                    cb.like(cb.lower(root.get("fingerprint")), like)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "last_updated_at_asc" -> Sort.by(Sort.Direction.ASC, "updatedAt");
            case "first_uploaded_at_desc" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "first_uploaded_at_asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            default -> Sort.by(Sort.Direction.DESC, "updatedAt");
        };
    }

    private static Long epoch(java.time.Instant instant) {
        return instant == null ? null : instant.getEpochSecond();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ContactBookItem(
        String id,
        String userUid,
        String userNickname,
        String contactName,
        String contactPhone,
        String contactPhoneMasked,
        String contactRemark,
        String source,
        boolean isPlatformUser,
        String relatedUid,
        Long firstUploadedAt,
        Long lastUpdatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ContactBookListResponse(
        List<ContactBookItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}
}
