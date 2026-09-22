package com.chat99.server.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UserFriendSyncJob {

    private static final Logger log = LoggerFactory.getLogger(UserFriendSyncJob.class);

    private final UserFriendSyncProperties props;
    private final UserFriendSyncService syncService;

    public UserFriendSyncJob(UserFriendSyncProperties props, UserFriendSyncService syncService) {
        this.props = props;
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${chat99.user-friend-sync.scheduled-interval-ms:300000}")
    public void tick() {
        if (!props.scheduledEnabled()) {
            return;
        }
        UserFriendSyncService.SyncSnapshot snapshot = syncService.runScheduledTick();
        if (snapshot.scanned() > 0 && snapshot.completed()) {
            log.info("user friend sync scheduled run finished");
        }
    }
}
