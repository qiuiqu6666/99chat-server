package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class FriendAddVerifyController {

    private final UserRepository userRepository;
    private final UserPrivacyService privacyService;

    public FriendAddVerifyController(UserRepository userRepository, UserPrivacyService privacyService) {
        this.userRepository = userRepository;
        this.privacyService = privacyService;
    }

    public record FriendAddVerifyView(boolean friendAddRequiresVerify) {}

    public record FriendAddVerifyUpdateRequest(@NotNull Boolean friendAddRequiresVerify) {}

    @GetMapping("/me/friend-add-verify")
    public FriendAddVerifyView getMine(Authentication auth) {
        return toView(requireSelf(auth));
    }

    @PutMapping("/me/friend-add-verify")
    @Transactional
    public FriendAddVerifyView updateMine(Authentication auth,
                                          @Valid @RequestBody FriendAddVerifyUpdateRequest req) {
        User u = requireSelf(auth);
        u.setFriendAddRequiresVerify(req.friendAddRequiresVerify());
        userRepository.save(u);
        return toView(u);
    }

    @GetMapping("/users/{userId}/friend-add-verify")
    public FriendAddVerifyView getForUser(@PathVariable String userId) {
        return toView(privacyService.requireActiveUser(userId));
    }

    private User requireSelf(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private FriendAddVerifyView toView(User u) {
        return new FriendAddVerifyView(u.isFriendAddRequiresVerify());
    }
}
