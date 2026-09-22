package com.chat99.server.moments;

import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/moments")
public class MomentController {

    private final MomentService service;
    private final MomentSettingsService settingsService;

    public MomentController(MomentService service, MomentSettingsService settingsService) {
        this.service = service;
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public MomentSettingsService.SettingsView getSettings(Authentication auth) {
        return settingsService.getSettings(userId(auth));
    }

    @PutMapping("/settings")
    public MomentSettingsService.SettingsView updateSettings(Authentication auth, @RequestBody JsonNode body) {
        String userId = userId(auth);
        String coverUrl = null;
        boolean coverUrlProvided = false;
        if (body != null && body.has("coverUrl")) {
            coverUrlProvided = true;
            if (!body.get("coverUrl").isNull()) {
                coverUrl = body.get("coverUrl").asText(null);
            }
        }
        Integer visibleRangeDays = body != null && body.has("visibleRangeDays") && !body.get("visibleRangeDays").isNull()
            ? body.get("visibleRangeDays").asInt()
            : null;
        java.util.List<String> blockedViewerIds = body != null && body.has("blockedViewerIds") && body.get("blockedViewerIds").isArray()
            ? parseStringArray(body.get("blockedViewerIds"))
            : null;
        java.util.List<String> hiddenAuthorIds = body != null && body.has("hiddenAuthorIds") && body.get("hiddenAuthorIds").isArray()
            ? parseStringArray(body.get("hiddenAuthorIds"))
            : null;
        return settingsService.updateSettings(userId, coverUrl, coverUrlProvided, visibleRangeDays,
            blockedViewerIds, hiddenAuthorIds);
    }

    @PostMapping(value = "/cover/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MomentService.CoverUploadResult uploadCover(Authentication auth, @RequestParam("file") MultipartFile file)
        throws IOException {
        return service.uploadCover(userId(auth), file);
    }

    @GetMapping("/feed")
    public MomentService.PageView<MomentService.MomentItem> feed(
        Authentication auth,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer pageSize) {
        return service.feed(userId(auth), cursor, pageSize);
    }

    @GetMapping("/users/{targetUserId}")
    public MomentService.UserMomentPage userMoments(
        Authentication auth,
        @PathVariable String targetUserId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer pageSize) {
        return service.userMoments(userId(auth), targetUserId, cursor, pageSize);
    }

    @GetMapping("/{momentId}")
    public MomentService.MomentDetail detail(Authentication auth, @PathVariable String momentId) {
        return service.detail(userId(auth), momentId);
    }

    @PostMapping(value = "/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MomentService.MediaView uploadMedia(
        Authentication auth,
        @RequestParam("file") MultipartFile file,
        @RequestParam("type") String type,
        @RequestParam(value = "clientMediaId", required = false) String clientMediaId) throws IOException {
        return service.uploadMedia(userId(auth), file, type, clientMediaId);
    }

    @PostMapping
    public MomentService.MomentItem publish(
        Authentication auth,
        @Valid @RequestBody MomentService.PublishRequest body,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.publish(userId(auth), body, idempotencyKey);
    }

    @DeleteMapping("/{momentId}")
    public MomentService.DeleteMomentResult deleteMoment(
        Authentication auth,
        @PathVariable String momentId) {
        return service.deleteMoment(userId(auth), momentId);
    }

    @PostMapping("/{momentId}/likes")
    public MomentService.LikeResult like(Authentication auth, @PathVariable String momentId) {
        return service.like(userId(auth), momentId);
    }

    @DeleteMapping("/{momentId}/likes/me")
    public MomentService.LikeResult unlike(Authentication auth, @PathVariable String momentId) {
        return service.unlike(userId(auth), momentId);
    }

    @PostMapping("/{momentId}/comments")
    public MomentService.CommentCreateResult comment(
        Authentication auth,
        @PathVariable String momentId,
        @RequestBody MomentService.CommentRequest body,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.comment(userId(auth), momentId, body, idempotencyKey);
    }

    @DeleteMapping("/{momentId}/comments/{commentId}")
    public MomentService.CommentDeleteResult deleteComment(
        Authentication auth,
        @PathVariable String momentId,
        @PathVariable String commentId) {
        return service.deleteComment(userId(auth), momentId, commentId);
    }

    @GetMapping("/notifications")
    public MomentService.NotificationPage notifications(
        Authentication auth,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer pageSize) {
        return service.notifications(userId(auth), cursor, pageSize);
    }

    @PostMapping("/notifications/read")
    public MomentService.NotificationReadResult markNotificationsRead(
        Authentication auth,
        @RequestBody(required = false) MomentService.NotificationReadRequest body) {
        return service.markNotificationsRead(userId(auth), body);
    }

    private static String userId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return (String) auth.getPrincipal();
    }

    private static java.util.List<String> parseStringArray(JsonNode node) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                String value = item.asText(null);
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }
}
