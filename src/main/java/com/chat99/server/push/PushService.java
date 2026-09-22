package com.chat99.server.push;

import com.chat99.server.user.PresenceService;
import com.chat99.server.user.UserNotificationSettingsService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushConfigService pushConfig;
    private final UserPushTokenRepository tokenRepository;
    private final PushTokenService pushTokenService;
    private final ApnsPushSender apnsPushSender;
    private final JpushPushSender jpushPushSender;
    private final PresenceService presenceService;
    private final UserNotificationSettingsService notificationSettings;

    public PushService(PushConfigService pushConfig,
                       UserPushTokenRepository tokenRepository,
                       PushTokenService pushTokenService,
                       ApnsPushSender apnsPushSender,
                       JpushPushSender jpushPushSender,
                       PresenceService presenceService,
                       UserNotificationSettingsService notificationSettings) {
        this.pushConfig = pushConfig;
        this.tokenRepository = tokenRepository;
        this.pushTokenService = pushTokenService;
        this.apnsPushSender = apnsPushSender;
        this.jpushPushSender = jpushPushSender;
        this.presenceService = presenceService;
        this.notificationSettings = notificationSettings;
    }

    public boolean enabled() {
        return pushConfig.isPushEnabled() && (apnsPushSender.isReady() || jpushPushSender.isReady());
    }

    public void sendToUser(String userId, PushMessage message) {
        sendToUser(userId, message, pushConfig.isSkipWhenOnline());
    }

    /** 聊天离线 Push：默认不因在线心跳跳过（App 在后台仍可能收不到 IM 通知栏）。 */
    public void sendChatToUser(String userId, PushMessage message, boolean skipWhenOnline) {
        sendToUser(userId, message, skipWhenOnline);
    }

    private void sendToUser(String userId, PushMessage message, boolean skipWhenOnline) {
        if (!pushConfig.isPushEnabled() || userId == null || userId.isBlank() || message == null) {
            return;
        }
        if (!notificationSettings.isSystemMessageNotificationEnabled(userId)) {
            log.debug("push skipped: system message notification disabled userId={}", userId);
            return;
        }
        message = notificationSettings.maskForUser(userId, message);
        if (message.title() == null || message.title().isBlank()) {
            log.warn("push skipped: empty title userId={}", userId);
            return;
        }
        if (skipWhenOnline && presenceService.isLikelyOnline(userId)) {
            log.debug("push skipped: user online userId={}", userId);
            return;
        }
        List<UserPushToken> tokens = tokenRepository.findByUserIdAndApnsEnabledTrue(userId);
        if (tokens.isEmpty()) {
            log.debug("push skipped: no token userId={}", userId);
            return;
        }
        int sent = 0;
        int invalid = 0;
        int failed = 0;
        List<String> jpushIds = new ArrayList<>();
        List<UserPushToken> jpushTokens = new ArrayList<>();
        for (UserPushToken token : tokens) {
            if (token.getProvider() == PushProvider.APNS) {
                if (!apnsPushSender.isReady()) {
                    failed++;
                    continue;
                }
                PushSendResult result = apnsPushSender.send(token, message);
                if (result.invalidToken()) {
                    pushTokenService.disableToken(token.getId());
                    invalid++;
                } else if (result.sent()) {
                    sent++;
                } else {
                    failed++;
                }
            } else if (token.getProvider() == PushProvider.JPUSH) {
                if (jpushPushSender.isReady()) {
                    jpushIds.add(token.getPushToken());
                    jpushTokens.add(token);
                } else {
                    failed++;
                }
            }
        }
        if (!jpushIds.isEmpty()) {
            PushSendResult batch = jpushPushSender.sendBatch(jpushIds, message, userId, "batch");
            if (batch.sent()) {
                sent += jpushIds.size();
            } else if (batch.invalidToken()) {
                invalid += jpushIds.size();
                for (UserPushToken token : jpushTokens) {
                    pushTokenService.disableToken(token.getId());
                }
            } else {
                failed += jpushIds.size();
            }
        }
        if (sent > 0) {
            log.info("push sent userId={} devices={} candidates={} invalid={} failed={} title={}",
                userId, sent, tokens.size(), invalid, failed, message.title());
        } else {
            log.warn("push failed all devices userId={} candidates={} invalid={} failed={} title={}",
                userId, tokens.size(), invalid, failed, message.title());
        }
    }
}
