/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminAccess;
import com.chat99.server.adminapi.AdminApiException;
import com.chat99.server.adminapi.AdminAuditService;
import com.chat99.server.adminapi.AdminChatComplaintService;
import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.complaint.ChatComplaint;
import com.chat99.server.complaint.ChatComplaintRepository;
import com.chat99.server.complaint.ChatComplaintType;
import com.chat99.server.complaint.ComplaintReason;
import com.chat99.server.feedback.FeedbackStatus;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminChatComplaintService {
    private final ChatComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ObjectMapper json = new ObjectMapper();

    public AdminChatComplaintService(ChatComplaintRepository complaintRepository, UserRepository userRepository, AdminAuditService auditService) {
        this.complaintRepository = complaintRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public ComplaintListResponse list(String chatType, String reporterUid, String reportedUid, String groupId, String status, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Specification<ChatComplaint> spec = this.buildSpec(AdminChatComplaintService.blankToNull(chatType), AdminChatComplaintService.blankToNull(reporterUid), AdminChatComplaintService.blankToNull(reportedUid), AdminChatComplaintService.blankToNull(groupId), status);
        Page result = this.complaintRepository.findAll(spec, (Pageable)PageRequest.of((int)(safePage - 1), (int)safeSize, (Sort)Sort.by((Sort.Direction)Sort.Direction.DESC, (String[])new String[]{"createdAt"})));
        Map<String, UserProfile> profiles = this.loadProfiles(AdminChatComplaintService.collectUserIds(result.getContent()));
        List<ComplaintItem> items = result.getContent().stream().map(row -> this.toItem((ChatComplaint)row, profiles)).toList();
        return new ComplaintListResponse(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    @Transactional
    public ComplaintItem updateStatus(HttpServletRequest http, Authentication auth, long id, UpdateStatusRequest req) {
        AdminPrincipal admin = AdminAccess.require((Authentication)auth);
        AdminAccess.requirePermission((Authentication)auth, (String)"user.write");
        FeedbackStatus next = FeedbackStatus.fromApiCode((String)req.status());
        if (next == null) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "invalid_status", "invalid_status");
        }
        ChatComplaint row = (ChatComplaint)this.complaintRepository.findById(id).orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "complaint_not_found"));
        row.setStatus(next);
        if (req.reply() != null) {
            String reply = req.reply().trim();
            row.setAdminReply(reply.isEmpty() ? null : reply);
        }
        this.complaintRepository.save(row);
        this.auditService.log(http, admin.username(), "chat_complaint.status.update", row.getReportedUserId(), Map.of("complaintId", row.getId(), "status", next.getApiCode()));
        Map<String, UserProfile> profiles = this.loadProfiles(Set.of(row.getReporterUserId(), row.getReportedUserId()));
        return this.toItem(row, profiles);
    }

    private ComplaintItem toItem(ChatComplaint row, Map<String, UserProfile> profiles) {
        UserProfile reporter = profiles.get(row.getReporterUserId());
        UserProfile reported = profiles.get(row.getReportedUserId());
        FeedbackStatus status = row.getStatus() == null ? FeedbackStatus.PENDING : row.getStatus();
        ComplaintReason reason = row.getReason();
        return new ComplaintItem(String.valueOf(row.getId()), row.getChatType().getApiCode(), row.getReporterUserId(), reporter == null ? "\u2014" : reporter.nickname(), row.getReportedUserId(), reported == null ? "\u2014" : reported.nickname(), row.getGroupId(), reason == null ? "\u2014" : reason.getApiCode(), reason == null ? "\u2014" : reason.label(), row.getContent(), row.getMsgKey(), row.getMsgSeq(), this.parseImages(row.getScreenshotUrlsJson()), status.getApiCode(), status.label(), row.getAdminReply(), row.getClientVersion(), AdminChatComplaintService.toIso(row.getCreatedAt()), AdminChatComplaintService.toIso(row.getUpdatedAt()));
    }

    private List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> urls = json.readValue(raw, new TypeReference<List<String>>() {});
            return urls == null ? List.of() : urls.stream().filter(u -> u != null && !u.isBlank()).toList();
        }
        catch (Exception e) {
            return List.of();
        }
    }

    private Specification<ChatComplaint> buildSpec(String chatType, String reporterUid, String reportedUid, String groupId, String statusFilter) {
        return (Specification & Serializable)(root, query, cb) -> {
            FeedbackStatus status;
            ArrayList<Predicate> preds = new ArrayList<Predicate>();
            if (chatType != null) {
                try {
                    preds.add(cb.equal((Expression)root.get("chatType"), (Object)ChatComplaintType.fromApiCode(chatType)));
                }
                catch (IllegalArgumentException ignored) {
                    preds.add(cb.disjunction());
                }
            }
            if (reporterUid != null) {
                preds.add(cb.equal((Expression)root.get("reporterUserId"), (Object)reporterUid));
            }
            if (reportedUid != null) {
                preds.add(cb.equal((Expression)root.get("reportedUserId"), (Object)reportedUid));
            }
            if (groupId != null) {
                preds.add(cb.equal((Expression)root.get("groupId"), (Object)groupId));
            }
            if ((status = FeedbackStatus.fromApiCode((String)statusFilter)) != null) {
                preds.add(cb.equal((Expression)root.get("status"), (Object)status));
            }
            return cb.and((Predicate[])preds.toArray(Predicate[]::new));
        };
    }

    private Map<String, UserProfile> loadProfiles(Collection<String> userIds) {
        HashSet<String> ids = new HashSet<String>();
        for (String id : userIds) {
            if (id == null || id.isBlank()) continue;
            ids.add(id);
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        HashMap<String, UserProfile> out = new HashMap<String, UserProfile>();
        for (User u : this.userRepository.findByUserIdIn(ids)) {
            out.put(u.getUserId(), new UserProfile(u.getNickname(), u.getPhone()));
        }
        return out;
    }

    private static Set<String> collectUserIds(List<ChatComplaint> rows) {
        HashSet<String> ids = new HashSet<String>();
        for (ChatComplaint row : rows) {
            ids.add(row.getReporterUserId());
            ids.add(row.getReportedUserId());
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






    public record ComplaintItem(String id, String chatType, String reporterUid, String reporterNickname, String reportedUid, String reportedNickname, String groupId, String reason, String reasonLabel, String content, String msgKey, Long msgSeq, List<String> images, String status, String statusLabel, String reply, String clientVersion, String createdAt, String updatedAt) {}

    public record ComplaintListResponse(List<ComplaintItem> items, long total, int page, int pageSize, boolean hasMore) {}

    public record UpdateStatusRequest(String status, String reply) {}

    private record UserProfile(String nickname, String phone) {}
}
