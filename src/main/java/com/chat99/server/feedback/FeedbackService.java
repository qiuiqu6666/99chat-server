package com.chat99.server.feedback;

import com.chat99.server.group.GroupAvatarService;
import com.chat99.server.platform.PlatformProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final UserFeedbackRepository repository;
    private final GroupAvatarService avatarService;
    private final PlatformProperties platformProps;
    private final ObjectMapper json = new ObjectMapper();

    public FeedbackService(UserFeedbackRepository repository,
                           GroupAvatarService avatarService,
                           PlatformProperties platformProps) {
        this.repository = repository;
        this.avatarService = avatarService;
        this.platformProps = platformProps;
    }

    public record SubmitResult(long id, String type, String content, List<String> screenshotUrls,
                               String clientVersion, Instant createdAt) {}

    @Transactional
    public SubmitResult submit(String userId, FeedbackType type, String content, String clientVersion,
                               List<MultipartFile> screenshots) throws IOException {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String trimmed = content.trim();
        if (trimmed.length() > platformProps.maxFeedbackContentLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONTENT_TOO_LONG");
        }

        List<MultipartFile> files = screenshots == null ? List.of() : screenshots.stream()
            .filter(f -> f != null && !f.isEmpty())
            .toList();
        if (files.size() > platformProps.maxFeedbackScreenshots()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOO_MANY_SCREENSHOTS");
        }

        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) {
            String base = platformProps.feedbackPrefix() + userId + "/"
                + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
            GroupAvatarService.UploadResult uploaded = avatarService.uploadAvatar(file, base);
            urls.add(uploaded.previewUrl());
        }

        UserFeedback fb = new UserFeedback();
        fb.setUserId(userId);
        fb.setFeedbackType(type);
        fb.setContent(trimmed);
        fb.setScreenshotUrlsJson(toJson(urls));
        fb.setClientVersion(normalizeClientVersion(clientVersion));
        repository.save(fb);

        log.info("feedback submitted id={} userId={} type={} clientVersion={} screenshots={}",
            fb.getId(), userId, type, fb.getClientVersion(), urls.size());

        return new SubmitResult(
            fb.getId(),
            type.getApiCode(),
            trimmed,
            urls,
            fb.getClientVersion(),
            fb.getCreatedAt());
    }

    private static String normalizeClientVersion(String clientVersion) {
        if (clientVersion == null || clientVersion.isBlank()) {
            return null;
        }
        String v = clientVersion.trim();
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    private String toJson(List<String> urls) {
        if (urls.isEmpty()) return null;
        try {
            return json.writeValueAsString(urls);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize screenshot urls", e);
        }
    }
}
