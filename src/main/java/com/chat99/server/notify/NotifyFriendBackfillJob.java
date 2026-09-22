package com.chat99.server.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotifyFriendBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(NotifyFriendBackfillJob.class);

    private final NotifyFriendBackfillProperties props;
    private final NotifyFriendBackfillService backfillService;

    public NotifyFriendBackfillJob(NotifyFriendBackfillProperties props,
                                   NotifyFriendBackfillService backfillService) {
        this.props = props;
        this.backfillService = backfillService;
    }

    @Scheduled(fixedDelayString = "${chat99.notify-friend-backfill.scheduled-interval-ms:60000}")
    public void tick() {
        if (!props.scheduledEnabled()) {
            return;
        }
        NotifyFriendBackfillService.BackfillSnapshot snapshot = backfillService.runScheduledTick();
        if (snapshot.scanned() > 0 && snapshot.completed()) {
            log.info("notify friend backfill scheduled run finished");
        }
    }
}
