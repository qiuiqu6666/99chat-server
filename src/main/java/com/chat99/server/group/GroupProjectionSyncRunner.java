package com.chat99.server.group;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class GroupProjectionSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GroupProjectionSyncRunner.class);

    private final GroupProjectionSyncProperties props;
    private final GroupProjectionSyncService syncService;

    public GroupProjectionSyncRunner(GroupProjectionSyncProperties props,
                                     GroupProjectionSyncService syncService) {
        this.props = props;
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.runOnStartup()) {
            return;
        }
        log.info("group projection sync startup trigger maxGroups={}", props.maxGroupsOnStartup());
        GroupProjectionSyncService.SyncRequest request = new GroupProjectionSyncService.SyncRequest(
            props.maxGroupsOnStartup(),
            false,
            0,
            GroupProjectionSyncService.SyncRequest.defaults().groupTypes());
        if (!syncService.startAsync(request)) {
            log.warn("group projection sync startup skipped: already running");
        }
    }
}
