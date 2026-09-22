package com.chat99.server.adminapi;

import com.chat99.server.official.OfficialAccountService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/official-accounts")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminOfficialAccountsController {

    private final OfficialAccountService service;

    public AdminOfficialAccountsController(OfficialAccountService service) {
        this.service = service;
    }

    public record CreateRequest(
        @NotBlank String slug,
        @NotBlank String name,
        String introduction,
        String faceUrl,
        String organization,
        String ownerUserId,
        Integer maxSubscriberNum,
        Integer sortOrder) {}

    public record UpdateRequest(
        String name,
        String introduction,
        String faceUrl,
        String organization,
        Integer maxSubscriberNum,
        Boolean enabled,
        Integer sortOrder) {}

    public record BroadcastRequest(@NotBlank String text) {}

    public record LinkRequest(
        @NotBlank String officialAccountId,
        String slug,
        String name,
        String introduction,
        String faceUrl,
        String organization,
        String ownerUserId,
        Integer sortOrder) {}

    @GetMapping
    public List<OfficialAccountService.OfficialAccountView> list(Authentication auth) {
        AdminAccess.requirePermission(auth, "user.read");
        return service.listAll();
    }

    @PostMapping
    public OfficialAccountService.OfficialAccountView create(Authentication auth,
                                                             @Valid @RequestBody CreateRequest req) {
        AdminAccess.requirePermission(auth, "user.write");
        return service.create(new OfficialAccountService.CreateCommand(
            req.slug(), req.name(), req.introduction(), req.faceUrl(), req.organization(),
            req.ownerUserId(), req.maxSubscriberNum(), req.sortOrder()));
    }

    @PostMapping("/link")
    public OfficialAccountService.OfficialAccountView link(Authentication auth,
                                                           @Valid @RequestBody LinkRequest req) {
        AdminAccess.requirePermission(auth, "user.write");
        return service.linkExisting(new OfficialAccountService.LinkCommand(
            req.officialAccountId(), req.slug(), req.name(), req.introduction(), req.faceUrl(),
            req.organization(), req.ownerUserId(), req.sortOrder()));
    }

    @PatchMapping("/{officialAccountId}")
    public OfficialAccountService.OfficialAccountView update(Authentication auth,
                                                             @PathVariable String officialAccountId,
                                                             @RequestBody UpdateRequest req) {
        AdminAccess.requirePermission(auth, "user.write");
        return service.update(officialAccountId, new OfficialAccountService.UpdateCommand(
            req.name(), req.introduction(), req.faceUrl(), req.organization(),
            req.maxSubscriberNum(), req.enabled(), req.sortOrder()));
    }

    @DeleteMapping("/{officialAccountId}")
    public Map<String, String> delete(Authentication auth, @PathVariable String officialAccountId) {
        AdminAccess.requirePermission(auth, "user.write");
        service.delete(officialAccountId);
        return Map.of("official_account_id", officialAccountId, "status", "deleted");
    }

    @PostMapping("/{officialAccountId}/broadcast")
    public Map<String, Object> broadcast(Authentication auth,
                                         @PathVariable String officialAccountId,
                                         @Valid @RequestBody BroadcastRequest req) {
        AdminAccess.requirePermission(auth, "user.write");
        return service.broadcast(officialAccountId, req.text());
    }
}
