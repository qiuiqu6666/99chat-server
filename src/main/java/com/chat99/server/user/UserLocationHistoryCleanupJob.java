package com.chat99.server.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserLocationHistoryCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UserLocationHistoryCleanupJob.class);

    private final UserLocationHistoryRepository historyRepository;
    private final LocationProperties props;

    public UserLocationHistoryCleanupJob(UserLocationHistoryRepository historyRepository,
                                         LocationProperties props) {
        this.historyRepository = historyRepository;
        this.props = props;
    }

    @Scheduled(cron = "${chat99.location.history-cleanup-cron:0 40 4 * * *}")
    @Transactional
    public void cleanup() {
        Instant before = Instant.now().minus(props.historyRetentionDays(), ChronoUnit.DAYS);
        int deleted = historyRepository.deleteByCollectedAtBefore(before);
        if (deleted > 0) {
            log.info("user_location_history cleanup deleted={} retentionDays={}",
                deleted, props.historyRetentionDays());
        }
    }
}
