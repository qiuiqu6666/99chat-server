package com.chat99.server.feedback;

import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * multipart/form-data:
     * - type: suggestion | bug | other
     * - content: 反馈正文
     * - clientVersion: 可选，客户端版本号（建议自动带上）
     * - screenshots: 可选，多张图片，字段名重复传 screenshots
     */
    @PostMapping("/feedback")
    public FeedbackService.SubmitResult submit(Authentication auth,
                                               @RequestParam("type") String type,
                                               @RequestParam("content") String content,
                                               @RequestParam(value = "clientVersion", required = false)
                                               String clientVersion,
                                               @RequestPart(value = "screenshots", required = false)
                                               List<MultipartFile> screenshots) throws IOException {
        FeedbackType feedbackType;
        try {
            feedbackType = FeedbackType.fromApiCode(type);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_FEEDBACK_TYPE");
        }
        String userId = (String) auth.getPrincipal();
        return feedbackService.submit(userId, feedbackType, content, clientVersion, screenshots);
    }
}
