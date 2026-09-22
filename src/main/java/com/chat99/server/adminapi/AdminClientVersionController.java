package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
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

@RestController
@RequestMapping("/api/v1/client-versions")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminClientVersionController {

    private final AdminClientVersionService clientVersionService;

    public AdminClientVersionController(AdminClientVersionService clientVersionService) {
        this.clientVersionService = clientVersionService;
    }

    @GetMapping
    public AdminClientVersionService.ClientVersionListResponse list(
            Authentication auth,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return clientVersionService.list(platform, keyword, enabled, page, pageSize);
    }

    @PostMapping
    public AdminClientVersionService.ClientVersionItem create(
            HttpServletRequest http,
            Authentication auth,
            @RequestBody AdminClientVersionService.SaveBody body) {
        return clientVersionService.create(http, auth, body);
    }

    @PutMapping("/{id}")
    public AdminClientVersionService.ClientVersionItem update(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable long id,
            @RequestBody AdminClientVersionService.SaveBody body) {
        return clientVersionService.update(http, auth, id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<MapResponse> delete(
            HttpServletRequest http,
            Authentication auth,
            @PathVariable long id) {
        clientVersionService.delete(http, auth, id);
        return ResponseEntity.ok(new MapResponse(true));
    }

    public record MapResponse(boolean ok) {}
}
