package com.chat99.server.official;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/official-accounts")
public class OfficialAccountAdminController {

    private final AdminGuard guard;
    private final OfficialAccountService service;

    public OfficialAccountAdminController(AdminGuard guard, OfficialAccountService service) {
        this.guard = guard;
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
    public List<OfficialAccountService.OfficialAccountView> list(HttpServletRequest http) {
        guard.check(http);
        return service.listAll();
    }

    @PostMapping
    public OfficialAccountService.OfficialAccountView create(@Valid @RequestBody CreateRequest req,
                                                             HttpServletRequest http) {
        guard.check(http);
        return service.create(new OfficialAccountService.CreateCommand(
            req.slug(), req.name(), req.introduction(), req.faceUrl(), req.organization(),
            req.ownerUserId(), req.maxSubscriberNum(), req.sortOrder()));
    }

    /** 绑定腾讯云控制台已创建的公众号（不调用 create_official_account）。 */
    @PostMapping("/link")
    public OfficialAccountService.OfficialAccountView link(@Valid @RequestBody LinkRequest req,
                                                           HttpServletRequest http) {
        guard.check(http);
        return service.linkExisting(new OfficialAccountService.LinkCommand(
            req.officialAccountId(), req.slug(), req.name(), req.introduction(), req.faceUrl(),
            req.organization(), req.ownerUserId(), req.sortOrder()));
    }

    @PatchMapping("/{officialAccountId}")
    public OfficialAccountService.OfficialAccountView update(@PathVariable String officialAccountId,
                                                             @RequestBody UpdateRequest req,
                                                             HttpServletRequest http) {
        guard.check(http);
        return service.update(officialAccountId, new OfficialAccountService.UpdateCommand(
            req.name(), req.introduction(), req.faceUrl(), req.organization(),
            req.maxSubscriberNum(), req.enabled(), req.sortOrder()));
    }

    @DeleteMapping("/{officialAccountId}")
    public Map<String, String> delete(@PathVariable String officialAccountId, HttpServletRequest http) {
        guard.check(http);
        service.delete(officialAccountId);
        return Map.of("officialAccountId", officialAccountId, "status", "deleted");
    }

    @PostMapping("/{officialAccountId}/broadcast")
    public Map<String, Object> broadcast(@PathVariable String officialAccountId,
                                         @Valid @RequestBody BroadcastRequest req,
                                         HttpServletRequest http) {
        guard.check(http);
        return service.broadcast(officialAccountId, req.text());
    }
}
