package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/phone-album")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminPhoneAlbumController {

    private final AdminUserDetailService detailService;

    public AdminPhoneAlbumController(AdminUserDetailService detailService) {
        this.detailService = detailService;
    }

    @GetMapping("/config")
    public AdminUserDetailService.PhoneAlbumConfigResponse config(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid) {
        AdminAccess.requirePermission(auth, "user.read");
        return detailService.phoneAlbumConfig(userUid);
    }

    @GetMapping("/list")
    public AdminUserDetailService.PhoneAlbumListResponse list(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "100") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return detailService.phoneAlbumList(userUid, page, pageSize);
    }
}
