package com.chat99.server.realtime;

import com.chat99.server.group.GroupFanoutExecutorConfig;
import com.chat99.server.group.GroupFanoutProperties;
import com.chat99.server.realtime.GroupRealtimePublisher.GroupChangedEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class GroupRealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(GroupRealtimeNotifier.class);

    private final RealtimeEventPublisher publisher;
    private final GroupRealtimeOfflinePushService offlinePush;
    private final RealtimeProperties props;
    private final GroupFanoutProperties fanoutProps;
    private final Executor fanoutExecutor;

    public GroupRealtimeNotifier(RealtimeEventPublisher publisher,
                                 GroupRealtimeOfflinePushService offlinePush,
                                 RealtimeProperties props,
                                 GroupFanoutProperties fanoutProps,
                                 @Qualifier(GroupFanoutExecutorConfig.BEAN_NAME) Executor fanoutExecutor) {
        this.publisher = publisher;
        this.offlinePush = offlinePush;
        this.props = props;
        this.fanoutProps = fanoutProps == null ? GroupFanoutProperties.defaults() : fanoutProps;
        this.fanoutExecutor = fanoutExecutor;
    }

    @EventListener
    public void onGroupChanged(GroupChangedEvent event) {
        if (!props.enabled()) {
            return;
        }
        List<String> targets = event.targetUserIds();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        Map<String, Object> payload = GroupChangedPayloadBuilder.build(event);
        boolean async = fanoutProps.asyncEnabled() && targets.size() > fanoutProps.syncThreshold();
        if (async) {
            log.info("group fanout mode=async groupId={} action={} targetCount={} chunkSize={}",
                event.groupId(), event.action(), targets.size(), fanoutProps.chunkSize());
            List<String> snapshot = List.copyOf(targets);
            fanoutExecutor.execute(() -> deliverChunked(event, payload, snapshot));
            return;
        }
        log.debug("group fanout mode=sync groupId={} action={} targetCount={}",
            event.groupId(), event.action(), targets.size());
        deliverAll(event, payload, targets);
    }

    private void deliverChunked(GroupChangedEvent event, Map<String, Object> payload, List<String> targets) {
        int chunkSize = fanoutProps.chunkSize();
        int chunks = 0;
        for (int i = 0; i < targets.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, targets.size());
            deliverAll(event, payload, targets.subList(i, end));
            chunks++;
        }
        log.info("group fanout async done groupId={} action={} targetCount={} chunkCount={}",
            event.groupId(), event.action(), targets.size(), chunks);
    }

    private void deliverAll(GroupChangedEvent event, Map<String, Object> payload, List<String> targets) {
        boolean skipBroadcastOffline = offlinePush.shouldSkipOfflinePushForBroadcast(event.action());
        List<String> offlineAllow = skipBroadcastOffline
            ? offlinePush.offlinePushAllowlist(event)
            : null;
        for (String userId : targets) {
            boolean delivered = publisher.sendToUser(userId, payload);
            if (delivered) {
                continue;
            }
            if (skipBroadcastOffline) {
                if (offlineAllow != null && offlineAllow.contains(userId)) {
                    offlinePush.sendIfTcpUnreachable(userId, event);
                }
                continue;
            }
            offlinePush.sendIfTcpUnreachable(userId, event);
        }
    }
}
