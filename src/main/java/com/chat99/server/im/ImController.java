package com.chat99.server.im;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/im")
public class ImController {

    private final UserSigService userSigService;
    private final ImUserIdService imUserIdService;

    public ImController(UserSigService userSigService, ImUserIdService imUserIdService) {
        this.userSigService = userSigService;
        this.imUserIdService = imUserIdService;
    }

    public record UserSigResponse(
        int sdkAppId,
        String userId,
        String imUserId,
        String userSig,
        int expiresIn
    ) {}

    @GetMapping("/user-sig")
    public UserSigResponse userSig(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        String imUserId = imUserIdService.resolveImUserIdForSign(userId);
        String sig = userSigService.sign(imUserId);
        return new UserSigResponse(
            userSigService.sdkAppId(),
            userId,
            imUserId,
            sig,
            userSigService.expireSeconds());
    }
}
