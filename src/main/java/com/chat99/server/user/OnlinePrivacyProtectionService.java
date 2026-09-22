package com.chat99.server.user;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OnlinePrivacyProtectionService {

    public record LastActiveVisibilityChangedEvent(
        String userId,
        LastActiveVisibility previous,
        LastActiveVisibility current) {}

    private final UserRepository userRepository;
    private final UserPrivacyService privacyService;
    private final ApplicationEventPublisher events;

    public OnlinePrivacyProtectionService(UserRepository userRepository,
                                          UserPrivacyService privacyService,
                                          ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.privacyService = privacyService;
        this.events = events;
    }

    public OnlinePrivacyProtectionController.OnlinePrivacyProtectionView getForUser(String userId) {
        return toView(privacyService.requireActiveUser(userId));
    }

    public OnlinePrivacyProtectionController.OnlinePrivacyProtectionView getForSelf(String userId) {
        return toView(requireSelf(userId));
    }

    @Transactional
    public OnlinePrivacyProtectionController.OnlinePrivacyProtectionView updateForSelf(
        String userId, LastActiveVisibility visibility) {
        User user = requireSelf(userId);
        LastActiveVisibility previous = privacyService.lastActiveVisibilityOf(user);
        if (previous == visibility) {
            return toView(user);
        }
        user.setLastActiveVisibility(visibility);
        userRepository.save(user);
        events.publishEvent(new LastActiveVisibilityChangedEvent(userId, previous, visibility));
        return toView(user);
    }

    private User requireSelf(String userId) {
        return userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private OnlinePrivacyProtectionController.OnlinePrivacyProtectionView toView(User user) {
        return new OnlinePrivacyProtectionController.OnlinePrivacyProtectionView(
            privacyService.lastActiveVisibilityOf(user));
    }
}
