package com.chat99.server.announcement;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/announcements")
public class AnnouncementAdminController {

    private final AdminGuard guard;
    private final AnnouncementService announcementService;
    private final AnnouncementGlobalPushService globalPushService;

    public AnnouncementAdminController(AdminGuard guard,
                                       AnnouncementService announcementService,
                                       AnnouncementGlobalPushService globalPushService) {
        this.guard = guard;
        this.announcementService = announcementService;
        this.globalPushService = globalPushService;
    }

    public record CreateBody(
        @NotNull AnnouncementType type,
        String targetUserId,
        @NotBlank String title,
        @NotBlank String body,
        String linkUrl,
        String payloadJson,
        Integer priority,
        Instant expireAt,
        String createdBy) {}

    public record UpdateBody(
        String title,
        String body,
        String linkUrl,
        String payloadJson,
        Integer priority,
        Instant expireAt,
        String targetUserId) {}

    @PostMapping
    public AnnouncementService.AnnouncementView create(@Valid @RequestBody CreateBody body,
                                                       HttpServletRequest http) {
        guard.check(http);
        return announcementService.createDraft(new AnnouncementService.CreateCommand(
            body.type(), body.targetUserId(), body.title(), body.body(), body.linkUrl(),
            body.payloadJson(), body.priority(), body.expireAt(), body.createdBy()));
    }

    @GetMapping
    public List<AnnouncementService.AnnouncementView> list(
        @RequestParam(value = "status", required = false) AnnouncementStatus status,
        HttpServletRequest http) {
        guard.check(http);
        return announcementService.listAdmin(status);
    }

    @GetMapping("/{id}")
    public AnnouncementService.AnnouncementView get(@PathVariable String id, HttpServletRequest http) {
        guard.check(http);
        return announcementService.getById(id);
    }

    @PatchMapping("/{id}")
    public AnnouncementService.AnnouncementView update(@PathVariable String id,
                                                       @RequestBody UpdateBody body,
                                                       HttpServletRequest http) {
        guard.check(http);
        return announcementService.updateDraft(id, new AnnouncementService.UpdateCommand(
            body.title(), body.body(), body.linkUrl(), body.payloadJson(),
            body.priority(), body.expireAt(), body.targetUserId()));
    }

    @PostMapping("/{id}/publish")
    public AnnouncementService.AnnouncementView publish(@PathVariable String id, HttpServletRequest http) {
        guard.check(http);
        return announcementService.publish(id);
    }

    @PostMapping("/{id}/revoke")
    public AnnouncementService.AnnouncementView revoke(@PathVariable String id, HttpServletRequest http) {
        guard.check(http);
        return announcementService.revoke(id);
    }

    @PostMapping("/{id}/push-im")
    public Map<String, Object> pushIm(@PathVariable String id, HttpServletRequest http) {
        guard.check(http);
        globalPushService.startPushAsync(id);
        return Map.of("ok", true, "queued", true);
    }
}
