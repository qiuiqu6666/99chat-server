/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.complaint;

import com.chat99.server.complaint.ChatComplaint;
import com.chat99.server.complaint.ChatComplaintRepository;
import com.chat99.server.complaint.ChatComplaintService;
import com.chat99.server.complaint.ChatComplaintType;
import com.chat99.server.complaint.ComplaintReason;
import com.chat99.server.feedback.FeedbackStatus;
import com.chat99.server.group.GroupAvatarService;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.platform.PlatformProperties;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatComplaintService {
    private static final Logger log = LoggerFactory.getLogger(ChatComplaintService.class);
    private static final int DEDUP_HOURS = 24;
    private final ChatComplaintRepository repository;
    private final UserRepository userRepository;
    private final GroupProjectionService groupProjection;
    private final GroupAvatarService avatarService;
    private final PlatformProperties platformProps;
    private final ObjectMapper json = new ObjectMapper();

    public ChatComplaintService(ChatComplaintRepository repository, UserRepository userRepository, GroupProjectionService groupProjection, GroupAvatarService avatarService, PlatformProperties platformProps) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.groupProjection = groupProjection;
        this.avatarService = avatarService;
        this.platformProps = platformProps;
    }

    @Transactional
    public SubmitResult submitC2c(String reporterUserId, SubmitRequest req) throws IOException {
        String reportedUserId = ChatComplaintService.normalizeUserId(req.reportedUserId(), "reportedUserId");
        this.rejectSelfReport(reporterUserId, reportedUserId);
        this.requireExistingUser(reportedUserId);
        ComplaintReason reason = this.parseReason(req.reason());
        String content = this.normalizeContent(req.content());
        String msgKey = ChatComplaintService.normalizeOptional(req.msgKey(), 128);
        List<String> screenshotUrls = this.uploadScreenshots(reporterUserId, req.screenshots());
        this.rejectDuplicate(reporterUserId, ChatComplaintType.C2C, reportedUserId, null, msgKey);
        ChatComplaint row = new ChatComplaint();
        row.setReporterUserId(reporterUserId);
        row.setChatType(ChatComplaintType.C2C);
        row.setReportedUserId(reportedUserId);
        row.setReason(reason);
        row.setContent(content);
        row.setMsgKey(msgKey);
        row.setMsgSeq(req.msgSeq());
        row.setScreenshotUrlsJson(this.toJson(screenshotUrls));
        row.setClientVersion(ChatComplaintService.normalizeClientVersion(req.clientVersion()));
        this.repository.save(row);
        log.info("chat complaint c2c id={} reporter={} reported={} reason={} msgKey={} screenshots={}", new String[]{row.getId(), reporterUserId, reportedUserId, reason.getApiCode(), msgKey, screenshotUrls.size()});
        return ChatComplaintService.toResult(row, screenshotUrls);
    }

    @Transactional
    public SubmitResult submitGroup(String reporterUserId, GroupSubmitRequest req) throws IOException {
        String groupId = ChatComplaintService.normalizeGroupId(req.groupId());
        String reportedUserId = ChatComplaintService.normalizeUserId(req.reportedUserId(), "reportedUserId");
        this.rejectSelfReport(reporterUserId, reportedUserId);
        this.groupProjection.findProfile(groupId).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
        this.groupProjection.findMember(groupId, reporterUserId).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER"));
        this.requireExistingUser(reportedUserId);
        ComplaintReason reason = this.parseReason(req.reason());
        String content = this.normalizeContent(req.content());
        String msgKey = ChatComplaintService.normalizeOptional(req.msgKey(), 128);
        List<String> screenshotUrls = this.uploadScreenshots(reporterUserId, req.screenshots());
        this.rejectDuplicate(reporterUserId, ChatComplaintType.GROUP, reportedUserId, groupId, msgKey);
        ChatComplaint row = new ChatComplaint();
        row.setReporterUserId(reporterUserId);
        row.setChatType(ChatComplaintType.GROUP);
        row.setReportedUserId(reportedUserId);
        row.setGroupId(groupId);
        row.setReason(reason);
        row.setContent(content);
        row.setMsgKey(msgKey);
        row.setMsgSeq(req.msgSeq());
        row.setScreenshotUrlsJson(this.toJson(screenshotUrls));
        row.setClientVersion(ChatComplaintService.normalizeClientVersion(req.clientVersion()));
        this.repository.save(row);
        log.info("chat complaint group id={} reporter={} group={} reported={} reason={} msgKey={} screenshots={}", new String[]{row.getId(), reporterUserId, groupId, reportedUserId, reason.getApiCode(), msgKey, screenshotUrls.size()});
        return ChatComplaintService.toResult(row, screenshotUrls);
    }

    private List<String> uploadScreenshots(String userId, List<MultipartFile> screenshots) throws IOException {
        List<MultipartFile> files;
        List<MultipartFile> list = files = screenshots == null ? List.of() : screenshots.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (files.size() > this.platformProps.maxFeedbackScreenshots()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "TOO_MANY_SCREENSHOTS");
        }
        ArrayList<String> urls = new ArrayList<String>();
        for (MultipartFile file : files) {
            String base = "complaint/" + userId + "/" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
            GroupAvatarService.UploadResult uploaded = this.avatarService.uploadAvatar(file, base);
            urls.add(uploaded.previewUrl());
        }
        return urls;
    }

    private void rejectSelfReport(String reporterUserId, String reportedUserId) {
        if (reporterUserId.equals(reportedUserId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "CANNOT_REPORT_SELF");
        }
    }

    private void requireExistingUser(String userId) {
        User user = this.userRepository.findByUserId(userId).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (user.getStatus() != 1) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "USER_NOT_REPORTABLE");
        }
    }

    private void rejectDuplicate(String reporterUserId, ChatComplaintType chatType, String reportedUserId, String groupId, String msgKey) {
        if (msgKey == null || msgKey.isBlank()) {
            return;
        }
        Instant since = Instant.now().minus(24L, ChronoUnit.HOURS);
        if (this.repository.existsByReporterUserIdAndChatTypeAndReportedUserIdAndGroupIdAndMsgKeyAndCreatedAtAfter(reporterUserId, chatType, reportedUserId, groupId, msgKey, since)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "COMPLAINT_ALREADY_SUBMITTED");
        }
    }

    private ComplaintReason parseReason(String raw) {
        try {
            return ComplaintReason.fromApiCode(raw);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_COMPLAINT_REASON");
        }
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.length() > this.platformProps.maxFeedbackContentLength()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "CONTENT_TOO_LONG");
        }
        return trimmed;
    }

    private static String normalizeUserId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String v = raw.trim();
        if (v.length() > 10) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return v;
    }

    private static String normalizeGroupId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String v = raw.trim();
        if (v.length() > 64) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return v;
    }

    private static String normalizeOptional(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        return v.length() > maxLen ? v.substring(0, maxLen) : v;
    }

    private static String normalizeClientVersion(String clientVersion) {
        if (clientVersion == null || clientVersion.isBlank()) {
            return null;
        }
        String v = clientVersion.trim();
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    private String toJson(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        try {
            return this.json.writeValueAsString(urls);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize screenshot urls", e);
        }
    }

    private static SubmitResult toResult(ChatComplaint row, List<String> screenshotUrls) {
        FeedbackStatus status = row.getStatus() == null ? FeedbackStatus.PENDING : row.getStatus();
        return new SubmitResult(row.getId().longValue(), row.getChatType().getApiCode(), row.getReportedUserId(), row.getGroupId(), row.getReason().getApiCode(), row.getContent(), row.getMsgKey(), row.getMsgSeq(), screenshotUrls == null ? List.of() : screenshotUrls, status.getApiCode(), row.getCreatedAt());
    }





    public record GroupSubmitRequest(String groupId, String reportedUserId, String reason, String content, String msgKey, Long msgSeq, String clientVersion, List<MultipartFile> screenshots) {}

    public record SubmitRequest(String reportedUserId, String reason, String content, String msgKey, Long msgSeq, String clientVersion, List<MultipartFile> screenshots) {}




    public record SubmitResult(long id, String chatType, String reportedUserId, String groupId, String reason, String content, String msgKey, Long msgSeq, List<String> screenshotUrls, String status, Instant createdAt) {}
}
