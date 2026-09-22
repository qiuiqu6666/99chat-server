package com.chat99.server.user;

import com.chat99.server.common.PhoneUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserSearchService {

    private final UserRepository userRepository;
    private final PhoneUtils phoneUtils;
    private final SearchRateLimiter limiter;
    private final UserPrivacyService privacyService;

    public UserSearchService(UserRepository userRepository, PhoneUtils phoneUtils,
                             SearchRateLimiter limiter, UserPrivacyService privacyService) {
        this.userRepository = userRepository;
        this.phoneUtils = phoneUtils;
        this.limiter = limiter;
        this.privacyService = privacyService;
    }

    public enum Scene { PHONE, UID }

    public record SearchResult(String userId, String nickname, String avatarUrl,
                               String phoneMasked, Long lastActiveAt,
                               LastActiveVisibility lastActiveVisibility) {}

    public SearchResult search(String selfUserId, String keyword, String phoneCountry) {
        if (keyword.startsWith("@")) {
            keyword = keyword.substring(1);
        }
        if (keyword == null || keyword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        limiter.checkNotBlocked(selfUserId);

        Scene scene;
        User target = null;
        try {
            if (keyword.startsWith("+")) {
                scene = Scene.PHONE;
                PhoneUtils.Parsed p = phoneUtils.parseWithRegion(keyword, null);
                target = userRepository.findByPhone(p.e164()).orElse(null);
            } else if (keyword.matches("^[0-9]+$")) {
                scene = Scene.PHONE;
                String region = (phoneCountry == null || phoneCountry.isBlank()) ? "CN" : phoneCountry;
                PhoneUtils.Parsed p = phoneUtils.parseWithRegion(keyword, region);
                target = userRepository.findByPhone(p.e164()).orElse(null);
            } else if (keyword.matches("^[A-Za-z].*")) {
                scene = Scene.UID;
                target = userRepository.findByUserId(keyword).orElse(null);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }

        if (target != null && target.getUserId().equals(selfUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        boolean hit = target != null
            && target.getStatus() == 1
            && ((scene == Scene.PHONE && target.isAllowViaPhone())
                || (scene == Scene.UID && target.isAllowViaUid()));

        if (!hit) {
            limiter.recordMiss(selfUserId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        Long lastActiveMs = privacyService.lastActiveAtEpochMillis(target);
        return new SearchResult(
            target.getUserId(),
            target.getNickname(),
            target.getAvatarUrl(),
            phoneUtils.mask(target.getPhone()),
            lastActiveMs,
            privacyService.lastActiveVisibilityOf(target));
    }
}
