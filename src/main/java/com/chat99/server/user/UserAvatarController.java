package com.chat99.server.user;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UserAvatarController {

    private static final Logger log = LoggerFactory.getLogger(UserAvatarController.class);

    private final UserAvatarService userAvatarService;

    public UserAvatarController(UserAvatarService userAvatarService) {
        this.userAvatarService = userAvatarService;
    }

    @PostMapping("/me/avatar")
    public UserAvatarService.AvatarUpdateResult uploadAvatar(@RequestParam("file") MultipartFile file,
                                                             Authentication auth) throws IOException {
        String userId = (String) auth.getPrincipal();
        log.info("uploadUserAvatar userId={} contentType={} size={}",
            userId, file.getContentType(), file.getSize());
        UserAvatarService.AvatarUpdateResult r = userAvatarService.updateAvatar(userId, file);
        log.info("user avatar updated userId={} avatarUrl={}", userId, r.avatarUrl());
        return r;
    }
}
