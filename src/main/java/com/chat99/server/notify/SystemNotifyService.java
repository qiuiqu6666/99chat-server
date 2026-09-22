package com.chat99.server.notify;

import com.chat99.server.announcement.Announcement;
import com.chat99.server.announcement.AnnouncementType;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAnnouncementMediaResolver;
import com.chat99.server.im.ImC2cImageContent;
import com.chat99.server.im.ImC2cVideoContent;
import com.chat99.server.im.ImRestException;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.user.UserFriendService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemNotifyService {

    private static final Logger log = LoggerFactory.getLogger(SystemNotifyService.class);
    private static final int FRIEND_ADD_ATTEMPTS = 3;
    private static final long FRIEND_ADD_RETRY_MS = 500L;

    private final SystemNotifyProperties props;
    private final ImAdminClient imAdmin;
    private final ImAnnouncementMediaResolver mediaResolver;
    private final PushService pushService;
    private final UserFriendService userFriendService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SystemNotifyService(SystemNotifyProperties props,
                               ImAdminClient imAdmin,
                               ImAnnouncementMediaResolver mediaResolver,
                               PushService pushService,
                               UserFriendService userFriendService) {
        this.props = props;
        this.imAdmin = imAdmin;
        this.mediaResolver = mediaResolver;
        this.pushService = pushService;
        this.userFriendService = userFriendService;
    }

    public String senderUserId() {
        return props.senderUserId();
    }

    /** 注册成功：与 99Messenger 双向好友 + 欢迎语（失败不抛，不挡注册）。 */
    public void onUserRegistered(String userId) {
        if (props.autoFriendOnRegister()) {
            ensureFriend(userId);
        }
        if (props.registerWelcomeEnabled()) {
            sendWelcome(userId);
        }
    }

    /** 个人/全站公告 IM 推送：发送普通 C2C 文本/图片/视频（失败不抛）。 */
    public void sendAnnouncement(String userId, Announcement announcement) {
        if (userId == null || userId.isBlank() || announcement == null) {
            return;
        }
        addFriendWithRetry(userId);
        String scope = announcement.getType() == AnnouncementType.GLOBAL ? "GLOBAL" : "PERSONAL";
        Map<String, Object> payload = parsePayloadJson(announcement.getPayloadJson());
        String contentType = str(payload.get("contentType"));
        try {
            switch (contentType) {
                case "image" -> {
                    ImC2cImageContent image = mediaResolver.resolveImage(payload, announcement.getBody());
                    imAdmin.sendC2cImage(props.senderUserId(), userId, image);
                }
                case "video" -> {
                    ImC2cVideoContent video = mediaResolver.resolveVideo(payload, announcement.getBody());
                    imAdmin.sendC2cVideo(props.senderUserId(), userId, video);
                }
                default -> imAdmin.sendC2cText(props.senderUserId(), userId, announcement.getBody());
            }
            log.info("announcement im sent id={} userId={} scope={} type={}",
                announcement.getId(), userId, scope, contentType == null ? "text" : contentType);
        } catch (ImRestException e) {
            log.warn("announcement im failed id={} userId={} code={} msg={}",
                announcement.getId(), userId, e.imErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("announcement im failed id={} userId={} err={}",
                announcement.getId(), userId, e.getMessage());
        }
        pushService.sendToUser(userId, PushMessage.of(
                props.senderDisplayName(),
                pushBodyForAnnouncement(announcement, payload))
            .withData("type", "announcement")
            .withData("announcementId", announcement.getId())
            .withData("scope", scope));
    }

    private Map<String, Object> parsePayloadJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("announcement payload_json parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String pushBodyForAnnouncement(Announcement announcement, Map<String, Object> payload) {
        String contentType = str(payload.get("contentType"));
        if ("image".equals(contentType)) {
            return "[图片]";
        }
        if ("video".equals(contentType)) {
            return "[视频]";
        }
        return announcement.getBody();
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    public void ensureFriend(String userId) {
        if (!props.autoFriendOnRegister()) {
            return;
        }
        addFriendWithRetry(userId);
    }

    private void sendWelcome(String userId) {
        try {
            imAdmin.sendC2cText(props.senderUserId(), userId, props.registerWelcomeMessage());
            log.info("register welcome sent userId={} from={}", userId, props.senderUserId());
        } catch (ImRestException e) {
            log.warn("register welcome im failed userId={} code={} msg={}",
                userId, e.imErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("register welcome failed userId={} err={}", userId, e.getMessage());
        }
        pushService.sendToUser(userId, PushMessage.of(
            props.senderDisplayName(),
            props.registerWelcomeMessage())
            .withData("type", "register_welcome"));
    }

    private void addFriendWithRetry(String userId) {
        String senderId = props.senderUserId();
        for (int i = 1; i <= FRIEND_ADD_ATTEMPTS; i++) {
            try {
                userFriendService.bindMutualFriends(senderId, userId, false);
                log.info("system notify friend_bind ok from={} to={}", senderId, userId);
                return;
            } catch (Exception e) {
                log.warn("system notify friend_bind attempt {}/{} from={} to={} err={}",
                    i, FRIEND_ADD_ATTEMPTS, senderId, userId, e.getMessage());
            }
            if (i < FRIEND_ADD_ATTEMPTS) {
                sleepQuiet(FRIEND_ADD_RETRY_MS);
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
