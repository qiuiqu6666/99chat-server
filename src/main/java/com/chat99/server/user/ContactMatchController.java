package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContactMatchController {

    private final ContactMatchService service;

    public ContactMatchController(ContactMatchService service) {
        this.service = service;
    }

    public record MatchRequest(
        @NotEmpty List<String> phones,
        String phoneCountry,
        Boolean includeFriendStatus) {}

    @PostMapping("/users/contacts/match")
    public ContactMatchService.MatchResponse match(Authentication auth,
                                                 @Valid @RequestBody MatchRequest req) {
        String selfUserId = (String) auth.getPrincipal();
        return service.match(selfUserId, new ContactMatchService.MatchCommand(
            req.phones(),
            req.phoneCountry(),
            req.includeFriendStatus()));
    }
}
