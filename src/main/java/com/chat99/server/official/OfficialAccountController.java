package com.chat99.server.official;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/official-accounts")
public class OfficialAccountController {

    private final OfficialAccountService service;

    public OfficialAccountController(OfficialAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<OfficialAccountService.OfficialAccountView> list() {
        return service.listPublic();
    }

    @GetMapping("/{officialAccountId}")
    public OfficialAccountService.OfficialAccountView get(@PathVariable String officialAccountId) {
        return service.getPublic(officialAccountId);
    }

    @GetMapping("/subscribed/me")
    public List<Map<String, Object>> mySubscriptions(Authentication auth,
                                                     @RequestParam(defaultValue = "50") int limit,
                                                     @RequestParam(defaultValue = "0") int offset) {
        return service.listSubscribedFromIm((String) auth.getPrincipal(), limit, offset);
    }

    @PostMapping("/{officialAccountId}/subscribe")
    public Map<String, String> subscribe(Authentication auth, @PathVariable String officialAccountId) {
        service.subscribe(officialAccountId, (String) auth.getPrincipal());
        return Map.of("officialAccountId", officialAccountId, "status", "subscribed");
    }

    @DeleteMapping("/{officialAccountId}/subscribe")
    public Map<String, String> unsubscribe(Authentication auth, @PathVariable String officialAccountId) {
        service.unsubscribe(officialAccountId, (String) auth.getPrincipal());
        return Map.of("officialAccountId", officialAccountId, "status", "unsubscribed");
    }
}
