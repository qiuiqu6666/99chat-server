package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserSearchController {

    private final UserSearchService service;

    public UserSearchController(UserSearchService service) {
        this.service = service;
    }

    public record SearchRequest(@NotBlank String keyword, String phoneCountry) {}

    @PostMapping("/users/search")
    public UserSearchService.SearchResult search(Authentication auth,
                                                 @Valid @RequestBody SearchRequest req) {
        String selfUserId = (String) auth.getPrincipal();
        return service.search(selfUserId, req.keyword(), req.phoneCountry());
    }
}
