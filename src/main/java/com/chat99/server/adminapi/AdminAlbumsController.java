package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/albums")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminAlbumsController {

    private final AdminAlbumsService albumsService;

    public AdminAlbumsController(AdminAlbumsService albumsService) {
        this.albumsService = albumsService;
    }

    @GetMapping("/list")
    public AdminAlbumsService.AlbumListResponse list(
        Authentication auth,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(name = "file_type", required = false) String fileType,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "24") int pageSize,
        @RequestParam(defaultValue = "upload_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "user.read");
        return albumsService.list(userUid, fileType, keyword, page, pageSize, sort);
    }

    @GetMapping("/detail")
    public AdminAlbumsService.AlbumDetailResponse detail(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid) {
        AdminAccess.requirePermission(auth, "user.read");
        return albumsService.detail(userUid);
    }

    @PostMapping("/{id}/delete")
    public AdminAlbumsService.AlbumDeleteResponse delete(
        Authentication auth,
        HttpServletRequest http,
        @PathVariable("id") String id) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return albumsService.delete(http, admin.username(), id);
    }
}
