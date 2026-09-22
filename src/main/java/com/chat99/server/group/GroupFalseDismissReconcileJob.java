package com.chat99.server.group;

import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 轻量扫本地 dismissed 群，入队 VERIFY（alive 则由 handler restore）。吃 REST 队列限流，不阻塞业务。
 */
@Component
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class GroupFalseDismissReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(GroupFalseDismissReconcileJob.class);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_ENQUEUE_PER_RUN = 200;

    private final GroupProfileRepository profileRepository;
    private final ObjectProvider<ImRestQueuePublisher> queuePublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GroupFalseDismissReconcileJob(GroupProfileRepository profileRepository,
                                         ObjectProvider<ImRestQueuePublisher> queuePublisher) {
        this.profileRepository = profileRepository;
        this.queuePublisher = queuePublisher;
    }

    /** 每小时整点跑一次。 */
    @Scheduled(cron = "${chat99.im.false-dismiss-reconcile-cron:0 0 * * * ?}")
    public void enqueueVerifyForDismissed() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
            if (publisher == null) {
                return;
            }
            long total = profileRepository.countByDismissedTrue();
            int enqueued = 0;
            int page = 0;
            while (enqueued < MAX_ENQUEUE_PER_RUN) {
                Page<GroupProfile> slice =
                    profileRepository.findByDismissedTrue(PageRequest.of(page, PAGE_SIZE));
                if (slice.isEmpty()) {
                    break;
                }
                for (GroupProfile profile : slice) {
                    if (enqueued >= MAX_ENQUEUE_PER_RUN) {
                        break;
                    }
                    publisher.enqueueVerifyGroupExists(profile.getGroupId(), "scheduled_false_dismiss");
                    enqueued++;
                }
                if (!slice.hasNext()) {
                    break;
                }
                page++;
            }
            log.info("false-dismiss reconcile enqueued={} dismissedTotal={}", enqueued, total);
        } finally {
            running.set(false);
        }
    }
}
