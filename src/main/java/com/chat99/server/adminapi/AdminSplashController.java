package com.chat99.server.adminapi;

import com.chat99.server.platform.AppSplashConfig;
import com.chat99.server.platform.SplashService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/splash")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminSplashController {

    private final SplashService splashService;
    private final AdminAuditService auditService;

    public AdminSplashController(SplashService splashService, AdminAuditService auditService) {
        this.splashService = splashService;
        this.auditService = auditService;
    }

    @GetMapping
    public SplashListResponse list(Authentication auth) {
        AdminAccess.requirePermission(auth, "user.read");
        List<SplashItem> items = splashService.listAll().stream()
            .sorted(Comparator.comparing(AppSplashConfig::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::toItem)
            .toList();
        return new SplashListResponse(items, items.size());
    }

    @GetMapping("/{id}")
    public SplashItem get(Authentication auth, @PathVariable long id) {
        AdminAccess.requirePermission(auth, "user.read");
        return splashService.findById(id)
            .map(this::toItem)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "splash_not_found"));
    }

    /**
     * 上传启动图并写入配置：校验类型/可解码，转 WebP（≤1080×1920、≤1MB），CDN 长缓存。
     */
    @PostMapping
    public SplashItem create(
            HttpServletRequest http,
            Authentication auth,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "enabled", required = false, defaultValue = "true") boolean enabled,
            @RequestParam(value = "fit", required = false) String fit,
            @RequestParam(value = "start_at", required = false) String startAt,
            @RequestParam(value = "end_at", required = false) String endAt,
            @RequestParam(value = "min_app_version", required = false) String minAppVersion,
            @RequestParam(value = "platforms", required = false) String platforms,
            @RequestParam(value = "channels", required = false) String channels) throws IOException {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        AppSplashConfig row = splashService.createFromUpload(
            file,
            enabled,
            fit,
            parseInstant(startAt),
            parseInstant(endAt),
            minAppVersion,
            platforms,
            channels,
            admin.username());
        auditService.log(http, admin.username(), "splash.create", null,
            Map.of("id", row.getId(), "version", row.getVersion(), "enabled", row.isEnabled()));
        return toItem(row);
    }

    @PutMapping("/{id}")
    public SplashItem update(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable long id,
            @RequestBody UpdateBody body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        AppSplashConfig row = splashService.update(
            id,
            body.enabled(),
            body.fit(),
            parseInstant(body.startAt()),
            parseInstant(body.endAt()),
            body.clearStartAt(),
            body.clearEndAt(),
            body.minAppVersion(),
            body.platforms(),
            body.channels());
        auditService.log(http, admin.username(), "splash.update", null,
            Map.of("id", id, "version", row.getVersion(), "enabled", row.isEnabled()));
        return toItem(row);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OkResponse> delete(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable long id) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        splashService.delete(id);
        auditService.log(http, admin.username(), "splash.delete", null, Map.of("id", id));
        return ResponseEntity.ok(new OkResponse(true));
    }

    private SplashItem toItem(AppSplashConfig row) {
        return new SplashItem(
            String.valueOf(row.getId()),
            row.getVersion(),
            row.isEnabled(),
            row.getImageUrl(),
            row.getImageMd5(),
            row.getContentType(),
            row.getWidth(),
            row.getHeight(),
            row.getBytes(),
            row.getFit(),
            row.getStartAt() == null ? null : row.getStartAt().toString(),
            row.getEndAt() == null ? null : row.getEndAt().toString(),
            row.getMinAppVersion(),
            row.getPlatforms(),
            row.getChannels(),
            row.getCreatedBy(),
            row.getCreatedAt() == null ? null : row.getCreatedAt().toString(),
            row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid_instant");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SplashItem(
        String id,
        String version,
        boolean enabled,
        String imageUrl,
        String imageMd5,
        String contentType,
        Integer width,
        Integer height,
        Integer bytes,
        String fit,
        String startAt,
        String endAt,
        String minAppVersion,
        String platforms,
        String channels,
        String createdBy,
        String createdAt,
        String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SplashListResponse(List<SplashItem> items, long total) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateBody(
        Boolean enabled,
        String fit,
        String startAt,
        String endAt,
        Boolean clearStartAt,
        Boolean clearEndAt,
        String minAppVersion,
        String platforms,
        String channels) {}

    public record OkResponse(boolean ok) {}
}
