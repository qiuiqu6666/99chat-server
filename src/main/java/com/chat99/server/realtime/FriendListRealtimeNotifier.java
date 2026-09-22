package com.chat99.server.realtime;

import com.chat99.server.realtime.FriendListRealtimePublisher.FriendListChangedCommittedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class FriendListRealtimeNotifier {

    private final RealtimeEventPublisher publisher;
    private final FriendListOfflinePushService offlinePush;

    public FriendListRealtimeNotifier(RealtimeEventPublisher publisher,
                                      FriendListOfflinePushService offlinePush) {
        this.publisher = publisher;
        this.offlinePush = offlinePush;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(FriendListChangedCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", FriendListRealtimePublisher.EVENT);
        payload.put("action", event.action());
        payload.put("peerUserId", event.peerUserId());
        if (event.peerNickname() != null) {
            payload.put("peerNickname", event.peerNickname());
        }
        if (event.peerAvatarUrl() != null) {
            payload.put("peerAvatarUrl", event.peerAvatarUrl());
        }
        if (event.remark() != null) {
            payload.put("remark", event.remark());
        }
        if (event.addedAt() != null) {
            payload.put("addedAt", event.addedAt().toString());
        }
        payload.put("inMyFriendList", event.inMyFriendList());
        payload.put("isFriend", event.isFriend());
        payload.put("peerDeletedMe", event.peerDeletedMe());
        payload.put("canMessage", event.canMessage());
        payload.put("lastActiveAt", event.lastActiveAt());
        if (event.lastActiveVisibility() != null) {
            payload.put("lastActiveVisibility", event.lastActiveVisibility().name());
        }
        if (event.seq() > 0L) {
            payload.put("seq", event.seq());
        }
        boolean delivered = publisher.sendToUser(event.targetUserId(), payload);
        if (!delivered) {
            offlinePush.sendIfTcpUnreachable(event.targetUserId(), event);
        }
    }
}
