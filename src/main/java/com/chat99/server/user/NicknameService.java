package com.chat99.server.user;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NicknameService {

    private final UserRepository userRepository;
    private final NicknameProperties props;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final UserFriendService userFriendService;

    public NicknameService(UserRepository userRepository, NicknameProperties props, ImAdminClient imAdmin,
                           ImUserIdService imUserIdService,
                           UserFriendService userFriendService) {
        this.userRepository = userRepository;
        this.props = props;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.userFriendService = userFriendService;
    }

    public record NicknameUpdateResult(String nickname, Instant nextChangeableAt) {}

    public record NicknameCheckResult(
        boolean available,
        String reason,
        Instant nextChangeableAt) {}

    public String normalize(String nickname) {
        if (nickname == null) {
            return "";
        }
        return nickname.trim();
    }

    public void validateFormat(String nickname) {
        int len = nickname.length();
        if (len < props.minLength() || len > props.maxLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    /** 修改前检查：冷却中或已被占用则 available=false。 */
    public NicknameCheckResult checkChangeable(String userId, String rawNickname) {
        String nickname = normalize(rawNickname);
        validateFormat(nickname);
        User u = requireUser(userId);

        if (u.getNickname().equals(nickname)) {
            return new NicknameCheckResult(true, null, nextChangeableAt(u));
        }
        Instant unlock = cooldownUnlockAt(u);
        if (unlock != null && Instant.now().isBefore(unlock)) {
            return new NicknameCheckResult(false, "NICKNAME_COOLDOWN", unlock);
        }
        if (userRepository.existsByNicknameAndUserIdNot(nickname, userId)) {
            return new NicknameCheckResult(false, "NICKNAME_EXISTS", null);
        }
        return new NicknameCheckResult(true, null, Instant.now().plus(props.cooldownDays(), ChronoUnit.DAYS));
    }

    /** 任何人可调用：仅检查格式与是否已被占用。 */
    public NicknameCheckResult checkAvailable(String rawNickname) {
        String nickname = normalize(rawNickname);
        validateFormat(nickname);
        if (userRepository.existsByNickname(nickname)) {
            return new NicknameCheckResult(false, "NICKNAME_EXISTS", null);
        }
        return new NicknameCheckResult(true, null, null);
    }

    @Transactional
    public NicknameUpdateResult update(String userId, String rawNickname) {
        String nickname = normalize(rawNickname);
        validateFormat(nickname);
        User u = requireUser(userId);

        if (u.getNickname().equals(nickname)) {
            return new NicknameUpdateResult(u.getNickname(), nextChangeableAt(u));
        }

        Instant unlock = cooldownUnlockAt(u);
        if (unlock != null && Instant.now().isBefore(unlock)) {
            throw new NicknameCooldownException(unlock);
        }
        if (userRepository.existsByNicknameAndUserIdNot(nickname, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NICKNAME_EXISTS");
        }

        u.setNickname(nickname);
        u.setLastNicknameChangedAt(Instant.now());
        userRepository.save(u);
        imAdmin.profileUpdate(imUserIdService.toIm(u.getUserId()), nickname);
        userFriendService.onUserNicknameUpdated(u.getUserId(), nickname);

        return new NicknameUpdateResult(nickname, nextChangeableAt(u));
    }

    private User requireUser(String userId) {
        return userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    /** 冷却结束时刻；从未改过昵称则返回 null（可随时改）。 */
    private Instant cooldownUnlockAt(User u) {
        if (u.getLastNicknameChangedAt() == null) {
            return null;
        }
        return u.getLastNicknameChangedAt().plus(props.cooldownDays(), ChronoUnit.DAYS);
    }

    private Instant nextChangeableAt(User u) {
        Instant unlock = cooldownUnlockAt(u);
        if (unlock == null) {
            return Instant.now();
        }
        return unlock;
    }
}
