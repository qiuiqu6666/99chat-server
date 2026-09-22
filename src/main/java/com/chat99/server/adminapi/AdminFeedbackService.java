package com.chat99.server.adminapi;

import com.chat99.server.feedback.FeedbackStatus;
import com.chat99.server.feedback.FeedbackType;
import com.chat99.server.feedback.UserFeedback;
import com.chat99.server.feedback.UserFeedbackRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminFeedbackService {

    private final UserFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ObjectMapper json = new ObjectMapper();

    public AdminFeedbackService(UserFeedbackRepository feedbackRepository,
                                UserRepository userRepository,
                                AdminAuditService auditService) {
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public FeedbackListResponse list(String userUid, String keyword, String status,
                                     int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Specification<UserFeedback> spec = buildSpec(blankToNull(userUid), blankToNull(keyword), status);
        Page<UserFeedback> result = feedbackRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<String, UserProfile> profiles = loadProfiles(collectUserIds(result.getContent()));
        List<FeedbackItem> items = result.getContent().stream()
            .map(fb -> toItem(fb, profiles))
            .toList();
        return new FeedbackListResponse(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    @Transactional
    public FeedbackItem updateStatus(HttpServletRequest http, Authentication auth,
                                     long id, UpdateStatusRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        FeedbackStatus next = FeedbackStatus.fromApiCode(req.status());
        if (next == null) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "invalid_status", "invalid_status");
        }
        UserFeedback fb = feedbackRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "feedback_not_found"));
        fb.setStatus(next);
        if (req.reply() != null) {
            String reply = req.reply().trim();
            fb.setAdminReply(reply.isEmpty() ? null : reply);
        }
        feedbackRepository.save(fb);
        auditService.log(http, admin.username(), "feedback.status.update", fb.getUserId(),
            Map.of("feedbackId", fb.getId(), "status", next.getApiCode()));
        Map<String, UserProfile> profiles = loadProfiles(Set.of(fb.getUserId()));
        return toItem(fb, profiles);
    }

    private FeedbackItem toItem(UserFeedback fb, Map<String, UserProfile> profiles) {
        UserProfile profile = profiles.get(fb.getUserId());
        FeedbackStatus status = fb.getStatus() == null ? FeedbackStatus.PENDING : fb.getStatus();
        return new FeedbackItem(
            String.valueOf(fb.getId()),
            fb.getUserId(),
            profile == null ? "—" : profile.nickname(),
            profile == null ? null : profile.phone(),
            mapCategory(fb.getFeedbackType()),
            fb.getContent(),
            profile == null ? null : profile.phone(),
            parseImages(fb.getScreenshotUrlsJson()),
            status.getApiCode(),
            status.label(),
            fb.getAdminReply(),
            fb.getClientVersion(),
            toIso(fb.getCreatedAt()),
            toIso(fb.getUpdatedAt()));
    }

    private static String mapCategory(FeedbackType type) {
        if (type == null) {
            return "—";
        }
        return switch (type) {
            case SUGGESTION -> "建议";
            case BUG -> "Bug";
            case OTHER -> "其他";
        };
    }

    private List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> urls = json.readValue(raw, new TypeReference<>() {});
            return urls == null ? List.of() : urls.stream().filter(u -> u != null && !u.isBlank()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Specification<UserFeedback> buildSpec(String userUid, String keyword, String statusFilter) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            FeedbackStatus status = FeedbackStatus.fromApiCode(statusFilter);
            if (status != null) {
                preds.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("content")), like),
                    cb.like(cb.lower(root.get("adminReply")), like)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private Map<String, UserProfile> loadProfiles(Collection<String> userIds) {
        Set<String> ids = new HashSet<>();
        for (String id : userIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, UserProfile> out = new HashMap<>();
        for (User u : userRepository.findByUserIdIn(ids)) {
            out.put(u.getUserId(), new UserProfile(u.getNickname(), u.getPhone()));
        }
        return out;
    }

    private static Set<String> collectUserIds(List<UserFeedback> rows) {
        Set<String> ids = new HashSet<>();
        for (UserFeedback fb : rows) {
            ids.add(fb.getUserId());
        }
        return ids;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private record UserProfile(String nickname, String phone) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FeedbackItem(
        String id,
        String userUid,
        String nickname,
        String phone,
        String category,
        String content,
        String contact,
        List<String> images,
        String status,
        String statusLabel,
        String reply,
        String clientVersion,
        String createdAt,
        String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FeedbackListResponse(
        List<FeedbackItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateStatusRequest(String status, String reply) {}
}
