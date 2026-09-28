package com.chat99.server.user;

import com.chat99.server.realtime.PresenceRealtimePublisher;
import com.chat99.server.wallet.DepositScanEligibilityService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把心跳合并后的最后活跃时间批量写入主库。不用 {@code @Scheduled}，避免占用全局单调度线程。
 */
@Component
public class PresenceLastActiveFlushJob {

    private static final Logger log = LoggerFactory.getLogger(PresenceLastActiveFlushJob.class);
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final PresenceService presenceService;
    private final UserRepository userRepository;
    private final DepositScanEligibilityService depositScanEligibilityService;
    private final PresenceRealtimePublisher presenceRealtimePublisher;
    private final PresenceProperties props;
    private ScheduledExecutorService scheduler;

    public PresenceLastActiveFlushJob(PresenceService presenceService,
                                      UserRepository userRepository,
                                      DepositScanEligibilityService depositScanEligibilityService,
                                      PresenceRealtimePublisher presenceRealtimePublisher,
                                      PresenceProperties props) {
        this.presenceService = presenceService;
        this.userRepository = userRepository;
        this.depositScanEligibilityService = depositScanEligibilityService;
        this.presenceRealtimePublisher = presenceRealtimePublisher;
        this.props = props;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "presence-last-active-flush");
            t.setDaemon(true);
            return t;
        });
        long interval = props.lastActiveFlushIntervalMs();
        scheduler.scheduleWithFixedDelay(this::flushQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("presence last-active flush failed: {}", e.getMessage());
        }
    }

    void flush() {
        int batchSize = props.lastActiveFlushBatchSize();
        for (int batches = 0; batches < MAX_BATCHES_PER_RUN; batches++) {
            List<String> ids = presenceService.drainPendingLastActive(batchSize);
            if (ids.isEmpty()) {
                return;
            }
            Instant now = Instant.now();
            userRepository.touchLastActiveBatch(ids, now);
            for (String userId : ids) {
                depositScanEligibilityService.ensureRegistered(userId);
                presenceRealtimePublisher.userBecameActive(userId, now);
            }
        }
    }
}
