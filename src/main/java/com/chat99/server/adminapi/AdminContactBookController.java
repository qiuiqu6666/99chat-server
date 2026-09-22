package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/contact-book")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminContactBookController {

    private final AdminContactBookService contactBookService;

    public AdminContactBookController(AdminContactBookService contactBookService) {
        this.contactBookService = contactBookService;
    }

    @GetMapping("/list")
    public AdminContactBookService.ContactBookListResponse list(
        Authentication auth,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(required = false) String keyword,
        @RequestParam(name = "contact_name", required = false) String contactName,
        @RequestParam(name = "contact_phone", required = false) String contactPhone,
        @RequestParam(name = "hit_platform_user", required = false) String hitPlatformUser,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
        @RequestParam(defaultValue = "last_updated_at_desc") String sort) {
        AdminAccess.requirePermission(auth, "user.read");
        return contactBookService.list(
            userUid, keyword, contactName, contactPhone, hitPlatformUser, page, pageSize, sort);
    }

    @GetMapping("/search")
    public AdminContactBookService.ContactBookListResponse search(
        Authentication auth,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(required = false) String keyword,
        @RequestParam(name = "contact_name", required = false) String contactName,
        @RequestParam(name = "contact_phone", required = false) String contactPhone,
        @RequestParam(name = "hit_platform_user", required = false) String hitPlatformUser,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
        @RequestParam(defaultValue = "last_updated_at_desc") String sort) {
        AdminAccess.requirePermission(auth, "user.read");
        return contactBookService.list(
            userUid, keyword, contactName, contactPhone, hitPlatformUser, page, pageSize, sort);
    }
}
