package com.chat99.server.announcement;

import com.chat99.server.notify.PlatformWalletNoticeProperties;
import com.chat99.server.notify.SystemNotifyProperties;
import com.chat99.server.notify.SystemNotifyService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AnnouncementGlobalPushService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementGlobalPushService.class);

    private final AnnouncementGlobalPushProperties props;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final SystemNotifyService systemNotifyService;
    private final SystemNotifyProperties systemProps;
    private final PlatformWalletNoticeProperties walletProps;
    private final ConcurrentLinkedQueue<String> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> queuedIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AnnouncementGlobalPushService(AnnouncementGlobalPushProperties props,
                                         AnnouncementRepository announcementRepository,
                                         UserRepository userRepository,
                                         SystemNotifyService systemNotifyService,
                                         SystemNotifyProperties systemProps,
                                         PlatformWalletNoticeProperties walletProps) {
        this.props = props;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.systemNotifyService = systemNotifyService;
        this.systemProps = systemProps;
        this.walletProps = walletProps;
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 在事务提交后再异步全站推送，避免 publish 未提交时后台线程查不到公告。 */
    public void schedulePushAfterCommit(String announcementId) {
        if (!props.enabled()) {
            markSkipped(announcementId);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    startPushAsync(announcementId);
                }
            });
            return;
        }
        startPushAsync(announcementId);
    }

    /** 异步向全站用户推送（99Messenger C2C）；并发时入队顺序执行。 */
    public void startPushAsync(String announcementId) {
        if (!props.enabled()) {
            markSkipped(announcementId);
            return;
        }
        if (!queuedIds.add(announcementId)) {
            log.info("announcement global push already queued id={}", announcementId);
            return;
        }
        pendingQueue.offer(announcementId);
        log.info("announcement global push queued id={} queueSize={}", announcementId, pendingQueue.size());
        tryStartWorker();
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverPendingOnStartup() {
        if (!props.enabled()) {
            return;
        }
        Instant staleBefore = Instant.now().minus(10, ChronoUnit.MINUTES);
        List<Announcement> staleRunning = announcementRepository
            .findByTypeAndStatusAndImPushStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                AnnouncementType.GLOBAL, AnnouncementStatus.PUBLISHED,
                AnnouncementImPushStatus.RUNNING, staleBefore);
        for (Announcement announcement : staleRunning) {
            log.warn("announcement global push reset stale running id={}", announcement.getId());
            markPending(announcement.getId());
        }
        List<Announcement> pending = announcementRepository
            .findByTypeAndStatusAndImPushStatusOrderByPublishAtAsc(
                AnnouncementType.GLOBAL, AnnouncementStatus.PUBLISHED, AnnouncementImPushStatus.PENDING);
        for (Announcement announcement : pending) {
            log.info("announcement global push recover pending id={}", announcement.getId());
            startPushAsync(announcement.getId());
        }
    }

    private void tryStartWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                drainQueue();
            } finally {
                running.set(false);
                if (!pendingQueue.isEmpty()) {
                    tryStartWorker();
                }
            }
        }, "announcement-global-push");
        worker.setDaemon(true);
        worker.start();
    }

    private void drainQueue() {
        while (true) {
            String announcementId = pendingQueue.poll();
            if (announcementId == null) {
                return;
            }
            queuedIds.remove(announcementId);
            try {
                runPush(announcementId);
            } catch (Exception e) {
                log.error("announcement global push failed id={} err={}", announcementId, e.getMessage(), e);
                markFailed(announcementId);
            }
        }
    }

    @Transactional
    protected void markPending(String announcementId) {
        announcementRepository.findById(announcementId).ifPresent(a -> {
            a.setImPushStatus(AnnouncementImPushStatus.PENDING);
            announcementRepository.save(a);
        });
    }

    private void runPush(String announcementId) {
        Announcement announcement = announcementRepository.findById(announcementId)
            .orElseThrow(() -> new IllegalStateException("announcement not found: " + announcementId));
        if (announcement.getType() != AnnouncementType.GLOBAL) {
            return;
        }
        if (announcement.getStatus() != AnnouncementStatus.PUBLISHED) {
            return;
        }

        markRunning(announcementId);
        Instant activeSince = props.activeDays() > 0
            ? Instant.now().minus(props.activeDays(), ChronoUnit.DAYS)
            : null;

        long cursor = 0L;
        int sent = 0;
        int skipped = 0;
        String sender = systemProps.senderUserId();

        while (true) {
            Page<User> page = userRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
                1, cursor, PageRequest.of(0, props.batchSize()));
            if (page.isEmpty()) {
                break;
            }
            for (User user : page.getContent()) {
                cursor = user.getId();
                String userId = user.getUserId();
                if (isSystemAccount(userId)) {
                    skipped++;
                    continue;
                }
                if (activeSince != null) {
                    Instant lastActive = user.getLastActiveAt();
                    if (lastActive == null || lastActive.isBefore(activeSince)) {
                        skipped++;
                        continue;
                    }
                }
                systemNotifyService.sendAnnouncement(userId, announcement);
                sent++;
                sleepQuiet(props.delayBetweenUsersMs());
            }
            if (!page.hasNext()) {
                break;
            }
        }

        markDone(announcementId);
        log.info("announcement global push done id={} sender={} sent={} skipped={}",
            announcementId, sender, sent, skipped);
    }

    @Transactional
    protected void markRunning(String announcementId) {
        announcementRepository.findById(announcementId).ifPresent(a -> {
            a.setImPushStatus(AnnouncementImPushStatus.RUNNING);
            announcementRepository.save(a);
        });
    }

    @Transactional
    protected void markDone(String announcementId) {
        announcementRepository.findById(announcementId).ifPresent(a -> {
            a.setImPushStatus(AnnouncementImPushStatus.DONE);
            announcementRepository.save(a);
        });
    }

    @Transactional
    protected void markSkipped(String announcementId) {
        announcementRepository.findById(announcementId).ifPresent(a -> {
            a.setImPushStatus(AnnouncementImPushStatus.SKIPPED);
            announcementRepository.save(a);
        });
    }

    @Transactional
    protected void markFailed(String announcementId) {
        announcementRepository.findById(announcementId).ifPresent(a -> {
            a.setImPushStatus(AnnouncementImPushStatus.PENDING);
            announcementRepository.save(a);
        });
    }

    private boolean isSystemAccount(String userId) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        return userId.equals(systemProps.senderUserId())
            || userId.equals(walletProps.senderUserId());
    }

    private static void sleepQuiet(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
