package com.chat99.server.user;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeGameController {

    private final GamePrivilegeService gamePrivilegeService;

    public MeGameController(GamePrivilegeService gamePrivilegeService) {
        this.gamePrivilegeService = gamePrivilegeService;
    }

    @GetMapping("/me/game")
    public GamePrivilegeService.GameView getMyGame(Authentication auth) {
        return gamePrivilegeService.getForUser((String) auth.getPrincipal());
    }
}
