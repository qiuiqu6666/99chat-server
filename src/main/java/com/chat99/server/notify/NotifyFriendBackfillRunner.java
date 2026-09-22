package com.chat99.server.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class NotifyFriendBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NotifyFriendBackfillRunner.class);

    private final NotifyFriendBackfillProperties props;
    private final NotifyFriendBackfillService backfillService;

    public NotifyFriendBackfillRunner(NotifyFriendBackfillProperties props,
                                      NotifyFriendBackfillService backfillService) {
        this.props = props;
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.runOnStartup()) {
            return;
        }
        log.info("notify friend backfill startup trigger maxUsers={}", props.maxUsersOnStartup());
        if (!backfillService.startAsync(props.maxUsersOnStartup(), true)) {
            log.warn("notify friend backfill startup skipped: already running");
        }
    }
}
