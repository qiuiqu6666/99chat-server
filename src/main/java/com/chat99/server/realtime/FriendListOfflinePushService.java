package com.chat99.server.realtime;

import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.realtime.FriendListRealtimePublisher.FriendListChangedCommittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FriendListOfflinePushService {

    private static final Logger log = LoggerFactory.getLogger(FriendListOfflinePushService.class);
    public static final String PUSH_TYPE = "friend_list";

    private final PushService pushService;
    private final RealtimeProperties props;

    public FriendListOfflinePushService(PushService pushService, RealtimeProperties props) {
        this.pushService = pushService;
        this.props = props;
    }

    public void sendIfTcpUnreachable(String targetUserId, FriendListChangedCommittedEvent event) {
        if (!props.offlinePushEnabled()
            || !props.friendListOfflinePushEnabled()
            || !pushService.enabled()) {
            return;
        }
        if (targetUserId == null || targetUserId.isBlank() || event == null) {
            return;
        }
        PushMessage message = buildMessage(event);
        if (message == null) {
            return;
        }
        pushService.sendChatToUser(targetUserId, message, false);
        log.info("friend list offline push userId={} action={} peerUserId={}",
            targetUserId, event.action(), event.peerUserId());
    }

    private PushMessage buildMessage(FriendListChangedCommittedEvent event) {
        String peerName = event.peerNickname() != null && !event.peerNickname().isBlank()
            ? event.peerNickname()
            : event.peerUserId();
        String title = "好友列表更新";
        String body = switch (event.action()) {
            case FriendListRealtimePublisher.ACTION_ADDED ->
                "你与 " + peerName + " 已成为好友";
            case FriendListRealtimePublisher.ACTION_REMOVED ->
                "你已将 " + peerName + " 从通讯录移除";
            case FriendListRealtimePublisher.ACTION_UPDATED ->
                peerName + " 的好友关系有变化";
            case FriendListRealtimePublisher.ACTION_PROFILE_UPDATED,
                 FriendListRealtimePublisher.ACTION_REMARK_UPDATED -> null;
            default -> null;
        };
        if (body == null) {
            return null;
        }
        PushMessage message = PushMessage.of(title, body)
            .withData("type", PUSH_TYPE)
            .withData("event", FriendListRealtimePublisher.EVENT)
            .withData("action", event.action())
            .withData("peerUserId", event.peerUserId());
        String collapseId = "friend_list_" + event.action() + "_" + event.peerUserId();
        return message.withApnsGrouping(collapseId, PUSH_TYPE);
    }
}
