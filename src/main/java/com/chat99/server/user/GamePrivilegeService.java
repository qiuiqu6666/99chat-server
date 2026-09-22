package com.chat99.server.user;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GamePrivilegeService {

    public record GameView(boolean gameEnabled) {}

    public record GameAdminView(
        boolean gamePrivileged,
        boolean gameEnabledEffective,
        boolean masterEnabled) {}

    private final UserRepository userRepository;
    private final GamePrivilegeProperties props;

    public GamePrivilegeService(UserRepository userRepository, GamePrivilegeProperties props) {
        this.userRepository = userRepository;
        this.props = props;
    }

    public GameView getForUser(String userId) {
        User user = requireActiveUser(userId);
        return toView(user);
    }

    public GameAdminView getAdminView(String userId) {
        User user = requireUser(userId);
        return toAdminView(user);
    }

    /** 校验启用账号的有效游戏特权（masterEnabled && gamePrivileged）。 */
    public GameAdminView requireActivePrivileged(String userId) {
        User user = requireActiveUser(userId);
        GameAdminView view = toAdminView(user);
        if (!view.gameEnabledEffective()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GAME_PRIVILEGE_REQUIRED");
        }
        return view;
    }

    @Transactional
    public GameAdminView setPrivileged(String userId, boolean gamePrivileged) {
        User user = requireUser(userId);
        user.setGamePrivileged(gamePrivileged);
        userRepository.save(user);
        return toAdminView(user);
    }

    private GameView toView(User user) {
        return new GameView(props.masterEnabled() && user.isGamePrivileged());
    }

    private GameAdminView toAdminView(User user) {
        return new GameAdminView(
            user.isGamePrivileged(),
            props.masterEnabled() && user.isGamePrivileged(),
            props.masterEnabled());
    }

    private User requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return userRepository.findByUserId(userId.trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private User requireActiveUser(String userId) {
        User user = requireUser(userId);
        if (user.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER_DISABLED");
        }
        return user;
    }
}
