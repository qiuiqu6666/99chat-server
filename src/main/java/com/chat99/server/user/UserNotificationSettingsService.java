package com.chat99.server.user;

import com.chat99.server.push.PushMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserNotificationSettingsService {

    static final String APP_NOTIFICATION_TITLE = "99chat";
    static final String GENERIC_NOTIFICATION_BODY = "你收到了一条消息";

    private final UserRepository userRepository;

    public UserNotificationSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record NotificationSettingsView(
        boolean systemMessageNotificationEnabled,
        boolean callNotificationEnabled,
        NotificationDisplayContent notificationDisplayContent) {}

    public record NotificationSettingsUpdateRequest(
        Boolean systemMessageNotificationEnabled,
        Boolean callNotificationEnabled,
        NotificationDisplayContent notificationDisplayContent) {}

    public NotificationSettingsView getForUser(String userId) {
        return toView(requireActiveUser(userId));
    }

    public NotificationSettingsView updateForUser(String userId, NotificationSettingsUpdateRequest req) {
        User user = requireActiveUser(userId);
        if (req.systemMessageNotificationEnabled() != null) {
            user.setSystemMessageNotificationEnabled(req.systemMessageNotificationEnabled());
        }
        if (req.callNotificationEnabled() != null) {
            user.setCallNotificationEnabled(true);
        }
        if (req.notificationDisplayContent() != null) {
            user.setNotificationDisplayContent(req.notificationDisplayContent());
        }
        userRepository.save(user);
        return toView(user);
    }

    public boolean isSystemMessageNotificationEnabled(String userId) {
        return findPrefs(userId).systemMessageNotificationEnabled();
    }

    public boolean isCallNotificationEnabled(String userId) {
        return true;
    }

    public PushMessage maskForUser(String userId, PushMessage message) {
        if (message == null) {
            return null;
        }
        NotificationDisplayContent mode = findPrefs(userId).notificationDisplayContent();
        return switch (mode) {
            case show_all -> message;
            case generic -> PushMessage.of(APP_NOTIFICATION_TITLE, GENERIC_NOTIFICATION_BODY)
                .withApnsGrouping(message.collapseId(), message.threadId())
                .withDataMap(message.data());
            case hidden -> PushMessage.of(APP_NOTIFICATION_TITLE, "")
                .withApnsGrouping(message.collapseId(), message.threadId())
                .withDataMap(message.data());
        };
    }

    private NotificationSettingsView findPrefs(String userId) {
        if (userId == null || userId.isBlank()) {
            return defaults();
        }
        return userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .map(this::toView)
            .orElse(defaults());
    }

    private User requireActiveUser(String userId) {
        return userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private NotificationSettingsView toView(User user) {
        return new NotificationSettingsView(
            user.isSystemMessageNotificationEnabled(),
            true,
            user.getNotificationDisplayContent() == null
                ? NotificationDisplayContent.show_all
                : user.getNotificationDisplayContent());
    }

    private static NotificationSettingsView defaults() {
        return new NotificationSettingsView(true, true, NotificationDisplayContent.show_all);
    }
}
