package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/announcements")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminAnnouncementsController {

    private final AdminAnnouncementSendService sendService;
    private final AdminAnnouncementListService listService;

    public AdminAnnouncementsController(AdminAnnouncementSendService sendService,
                                        AdminAnnouncementListService listService) {
        this.sendService = sendService;
        this.listService = listService;
    }

    @GetMapping
    public AdminAnnouncementListService.AnnouncementListResponse list(
            Authentication auth,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "content_type", required = false) String contentType,
            @RequestParam(name = "scope_type", required = false) String scopeType,
            @RequestParam(name = "target_user_id", required = false) String targetUserId,
            @RequestParam(name = "im_push_status", required = false) String imPushStatus,
            @RequestParam(required = false) String status,
            @RequestParam(name = "created_by", required = false) String createdBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "10") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return listService.list(
            keyword, contentType, scopeType, targetUserId, imPushStatus, status, createdBy, page, pageSize);
    }

    @PostMapping("/send")
    public AdminAnnouncementSendService.SendResult send(
            HttpServletRequest http,
            Authentication auth,
            @RequestBody SendBody body) {
        return sendService.send(http, auth, new AdminAnnouncementSendService.SendRequest(
            body.contentType(),
            body.content(),
            body.imageUrl(),
            body.previewUrl(),
            body.thumbUrl(),
            body.width(),
            body.height(),
            body.imageSize(),
            body.previewSize(),
            body.thumbSize(),
            body.thumbWidth(),
            body.thumbHeight(),
            body.videoUrl(),
            body.videoSize(),
            body.videoSecond(),
            body.scope(),
            body.userUids(),
            body.scheduledAt()));
    }

    @PostMapping("/upload-image")
    public AdminAnnouncementSendService.ImageUploadResult uploadImage(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        return sendService.uploadImage(auth, file);
    }

    @PostMapping("/upload-video")
    public AdminAnnouncementSendService.VideoUploadResult uploadVideo(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        return sendService.uploadVideo(auth, file);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SendBody(
        String contentType,
        String content,
        String imageUrl,
        String previewUrl,
        String thumbUrl,
        Integer width,
        Integer height,
        Long imageSize,
        Long previewSize,
        Long thumbSize,
        Integer thumbWidth,
        Integer thumbHeight,
        String videoUrl,
        Long videoSize,
        Integer videoSecond,
        String scope,
        List<String> userUids,
        String scheduledAt) {}
}
