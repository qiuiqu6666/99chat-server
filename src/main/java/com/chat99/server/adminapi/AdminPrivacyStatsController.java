package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy/stats")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminPrivacyStatsController {

    private final AdminPrivacyStatsService privacyStatsService;

    public AdminPrivacyStatsController(AdminPrivacyStatsService privacyStatsService) {
        this.privacyStatsService = privacyStatsService;
    }

    @GetMapping("/summary")
    public AdminPrivacyStatsService.PrivacySummaryResponse summary(Authentication auth) {
        AdminAccess.requirePermission(auth, "user.read");
        return privacyStatsService.summary();
    }

    @GetMapping("/users")
    public AdminPrivacyStatsService.PrivacyUserStatsListResponse listUsers(
        Authentication auth,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "contact_count_desc") String sort,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return privacyStatsService.listUsers(keyword, sort, page, pageSize);
    }
}
