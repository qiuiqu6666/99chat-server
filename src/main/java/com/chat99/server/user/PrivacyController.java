package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PrivacyController {

    private final UserRepository userRepository;
    private final UserPrivacyService privacyService;

    public PrivacyController(UserRepository userRepository, UserPrivacyService privacyService) {
        this.userRepository = userRepository;
        this.privacyService = privacyService;
    }

    public record PrivacyView(boolean allowViaQrCode,
                              boolean allowViaCard,
                              boolean allowViaGroup,
                              boolean allowViaPhone,
                              boolean allowViaUid) {}

    public record PrivacyUpdateRequest(@NotNull Boolean allowViaQrCode,
                                       @NotNull Boolean allowViaCard,
                                       @NotNull Boolean allowViaGroup,
                                       @NotNull Boolean allowViaPhone,
                                       @NotNull Boolean allowViaUid) {}

    @GetMapping("/me/privacy")
    public PrivacyView get(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        return toView(u);
    }

    @PutMapping("/me/privacy")
    @Transactional
    public PrivacyView put(Authentication auth, @Valid @RequestBody PrivacyUpdateRequest req) {
        String userId = (String) auth.getPrincipal();
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        u.setAllowViaQrCode(req.allowViaQrCode());
        u.setAllowViaCard(req.allowViaCard());
        u.setAllowViaGroup(req.allowViaGroup());
        u.setAllowViaPhone(req.allowViaPhone());
        u.setAllowViaUid(req.allowViaUid());
        userRepository.save(u);
        return toView(u);
    }

    private PrivacyView toView(User u) {
        UserPrivacyService.PrivacyView v = privacyService.toView(u);
        return new PrivacyView(v.allowViaQrCode(), v.allowViaCard(), v.allowViaGroup(),
            v.allowViaPhone(), v.allowViaUid());
    }
}
