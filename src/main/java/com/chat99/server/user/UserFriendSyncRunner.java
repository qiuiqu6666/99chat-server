package com.chat99.server.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class UserFriendSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserFriendSyncRunner.class);

    private final UserFriendSyncProperties props;
    private final UserFriendSyncService syncService;

    public UserFriendSyncRunner(UserFriendSyncProperties props, UserFriendSyncService syncService) {
        this.props = props;
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.runOnStartup()) {
            return;
        }
        log.info("user friend sync startup trigger maxUsers={}", props.maxUsersOnStartup());
        if (!syncService.startAsync(props.maxUsersOnStartup(), true)) {
            log.warn("user friend sync startup skipped: already running");
        }
    }
}
