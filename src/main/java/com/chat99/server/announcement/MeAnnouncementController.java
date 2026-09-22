package com.chat99.server.announcement;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeAnnouncementController {

    private final AnnouncementService announcementService;

    public MeAnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/me/announcements")
    public Map<String, Object> list(Authentication auth,
                                      @RequestParam(value = "unreadOnly", defaultValue = "false")
                                      boolean unreadOnly,
                                      @RequestParam(value = "limit", defaultValue = "20") int limit) {
        String userId = (String) auth.getPrincipal();
        List<AnnouncementService.UserAnnouncementView> items =
            announcementService.listForUser(userId, unreadOnly, limit);
        return Map.of("items", items);
    }

    @GetMapping("/me/announcements/{id}")
    public AnnouncementService.UserAnnouncementView get(Authentication auth, @PathVariable String id) {
        String userId = (String) auth.getPrincipal();
        return announcementService.getForUser(userId, id);
    }

    @PostMapping("/me/announcements/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(Authentication auth, @PathVariable String id) {
        String userId = (String) auth.getPrincipal();
        announcementService.markRead(userId, id);
    }
}
