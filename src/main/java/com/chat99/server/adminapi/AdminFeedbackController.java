package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feedback")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminFeedbackController {

    private final AdminFeedbackService feedbackService;

    public AdminFeedbackController(AdminFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public AdminFeedbackService.FeedbackListResponse list(
            Authentication auth,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "10") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return feedbackService.list(userUid, keyword, status, page, pageSize);
    }

    @PostMapping("/{id}/status")
    public AdminFeedbackService.FeedbackItem updateStatus(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable long id,
            @RequestBody AdminFeedbackService.UpdateStatusRequest body) {
        return feedbackService.updateStatus(http, auth, id, body);
    }
}
