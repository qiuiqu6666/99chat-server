package com.chat99.server.realtime;

import com.chat99.server.push.PushDisplayNameResolver;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.realtime.FriendRequestRealtimeNotifier.FriendRequestCommittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FriendRequestOfflinePushService {

    private static final Logger log = LoggerFactory.getLogger(FriendRequestOfflinePushService.class);
    public static final String PUSH_TYPE = "friend_request";

    private final PushService pushService;
    private final PushDisplayNameResolver displayNameResolver;
    private final RealtimeProperties props;

    public FriendRequestOfflinePushService(PushService pushService,
                                             PushDisplayNameResolver displayNameResolver,
                                             RealtimeProperties props) {
        this.pushService = pushService;
        this.displayNameResolver = displayNameResolver;
        this.props = props;
    }

    public void sendIfTcpUnreachable(String targetUserId, FriendRequestCommittedEvent event) {
        if (!props.offlinePushEnabled() || !pushService.enabled()) {
            return;
        }
        if (targetUserId == null || targetUserId.isBlank() || event == null) {
            return;
        }
        String peerUserId = peerUserId(targetUserId, event);
        String displayName = displayNameResolver.resolveCallerDisplayName(targetUserId, peerUserId);
        if (displayName == null || displayName.isBlank()) {
            displayName = peerUserId;
        }
        PushMessage message = buildMessage(event, displayName);
        pushService.sendChatToUser(targetUserId, message, false);
        log.info("friend request offline push userId={} event={} requestId={}",
            targetUserId, event.event(), event.requestId());
    }

    private PushMessage buildMessage(FriendRequestCommittedEvent event, String peerName) {
        String title;
        String body = switch (event.event()) {
            case FriendRequestRealtimeNotifier.EVENT_RECEIVED -> {
                title = "新的好友申请";
                yield peerName + " 请求添加你为好友";
            }
            case FriendRequestRealtimeNotifier.EVENT_ACCEPTED -> {
                title = "好友申请已通过";
                yield peerName + " 已同意你的好友申请";
            }
            case FriendRequestRealtimeNotifier.EVENT_REJECTED -> {
                title = "好友申请未通过";
                yield peerName + " 拒绝了你的好友申请";
            }
            case FriendRequestRealtimeNotifier.EVENT_AUTO_ACCEPTED -> {
                title = "已成为好友";
                yield "你与 " + peerName + " 已成为好友";
            }
            case FriendRequestRealtimeNotifier.EVENT_RESTORED -> {
                title = "好友已恢复";
                yield "你与 " + peerName + " 已恢复好友关系";
            }
            default -> {
                title = "好友通知";
                yield peerName;
            }
        };
        PushMessage message = PushMessage.of(title, body)
            .withData("type", PUSH_TYPE)
            .withData("event", event.event())
            .withData("fromUserId", event.fromUserId())
            .withData("toUserId", event.toUserId());
        if (event.requestId() != null) {
            message = message.withData("requestId", Long.toString(event.requestId()));
        }
        if (event.addSource() != null) {
            message = message.withData("addSource", event.addSource());
        }
        String collapseId = event.requestId() != null
            ? "friend_req_" + event.requestId()
            : "friend_req_" + event.event() + "_" + event.fromUserId() + "_" + event.toUserId();
        return message.withApnsGrouping(collapseId, "friend_request");
    }

    private String peerUserId(String targetUserId, FriendRequestCommittedEvent event) {
        if (targetUserId.equals(event.toUserId())) {
            return event.fromUserId();
        }
        return event.toUserId();
    }
}
