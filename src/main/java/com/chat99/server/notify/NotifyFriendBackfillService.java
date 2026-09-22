package com.chat99.server.notify;

import com.chat99.server.user.User;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class NotifyFriendBackfillService {

    private static final Logger log = LoggerFactory.getLogger(NotifyFriendBackfillService.class);
    private static final int FRIEND_ADD_ATTEMPTS = 3;
    private static final long FRIEND_ADD_RETRY_MS = 500L;

    private final NotifyFriendBackfillProperties backfillProps;
    private final SystemNotifyProperties systemProps;
    private final PlatformWalletNoticeProperties walletProps;
    private final UserRepository userRepository;
    private final UserFriendService userFriendService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long cursorAfterId;
    private volatile BackfillSnapshot lastSnapshot = BackfillSnapshot.idle();

    public NotifyFriendBackfillService(NotifyFriendBackfillProperties backfillProps,
                                       SystemNotifyProperties systemProps,
                                       PlatformWalletNoticeProperties walletProps,
                                       UserRepository userRepository,
                                       UserFriendService userFriendService) {
        this.backfillProps = backfillProps;
        this.systemProps = systemProps;
        this.walletProps = walletProps;
        this.userRepository = userRepository;
        this.userFriendService = userFriendService;
    }

    public boolean isRunning() {
        return running.get();
    }

    public BackfillSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public long cursorAfterId() {
        return cursorAfterId;
    }

    public void resetCursor() {
        cursorAfterId = 0L;
    }

    /** 异步触发；已在跑则返回 false。 */
    public boolean startAsync(int maxUsers, boolean resetCursor) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                BackfillSnapshot snapshot = runInternal(maxUsers, resetCursor);
                lastSnapshot = snapshot;
            } catch (Exception e) {
                log.error("notify friend backfill failed err={}", e.getMessage(), e);
                lastSnapshot = BackfillSnapshot.failed(e.getMessage());
            } finally {
                running.set(false);
            }
        }, "notify-friend-backfill");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** 定时 Job 单次 tick；已在跑则跳过。 */
    public BackfillSnapshot runScheduledTick() {
        if (!running.compareAndSet(false, true)) {
            return lastSnapshot;
        }
        try {
            BackfillSnapshot snapshot = runInternal(backfillProps.maxUsersPerScheduledRun(), false);
            lastSnapshot = snapshot;
            return snapshot;
        } finally {
            running.set(false);
        }
    }

    private BackfillSnapshot runInternal(int maxUsers, boolean resetCursor) {
        if (resetCursor) {
            cursorAfterId = 0L;
        }
        int limit = maxUsers <= 0 ? Integer.MAX_VALUE : maxUsers;
        int scanned = 0;
        int systemOk = 0;
        int systemFail = 0;
        int walletOk = 0;
        int walletFail = 0;
        int skipped = 0;
        long startCursor = cursorAfterId;

        while (scanned < limit) {
            int pageSize = Math.min(backfillProps.batchSize(), limit - scanned);
            Page<User> page = userRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
                1, cursorAfterId, PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                cursorAfterId = 0L;
                break;
            }
            for (User user : page) {
                cursorAfterId = user.getId();
                String userId = user.getUserId();
                if (isSenderAccount(userId)) {
                    skipped++;
                    continue;
                }
                scanned++;
                if (backfillProps.systemNotifyEnabled()) {
                    if (addFriend(systemProps.senderUserId(), userId)) {
                        systemOk++;
                    } else {
                        systemFail++;
                    }
                }
                if (backfillProps.platformWalletEnabled()) {
                    if (addFriend(walletProps.senderUserId(), userId)) {
                        walletOk++;
                    } else {
                        walletFail++;
                    }
                }
                sleepQuiet(backfillProps.delayBetweenUsersMs());
                if (scanned >= limit) {
                    break;
                }
            }
            if (scanned >= limit) {
                break;
            }
        }

        boolean completed = cursorAfterId == 0L;
        BackfillSnapshot snapshot = new BackfillSnapshot(
            true, completed, startCursor, cursorAfterId, scanned, skipped,
            systemOk, systemFail, walletOk, walletFail, null);
        log.info("notify friend backfill scanned={} skipped={} systemOk={} systemFail={} walletOk={} walletFail={} completed={} cursor={}",
            scanned, skipped, systemOk, systemFail, walletOk, walletFail, completed, cursorAfterId);
        return snapshot;
    }

    private boolean isSenderAccount(String userId) {
        return userId.equals(systemProps.senderUserId()) || userId.equals(walletProps.senderUserId());
    }

    private boolean addFriend(String from, String to) {
        for (int i = 1; i <= FRIEND_ADD_ATTEMPTS; i++) {
            try {
                userFriendService.bindMutualFriends(from, to, false);
                return true;
            } catch (Exception e) {
                log.warn("notify friend backfill attempt {}/{} from={} to={} err={}",
                    i, FRIEND_ADD_ATTEMPTS, from, to, e.getMessage());
            }
            if (i < FRIEND_ADD_ATTEMPTS) {
                sleepQuiet(FRIEND_ADD_RETRY_MS);
            }
        }
        return false;
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

    public record BackfillSnapshot(
        boolean ok,
        boolean completed,
        long startCursor,
        long endCursor,
        int scanned,
        int skipped,
        int systemOk,
        int systemFail,
        int walletOk,
        int walletFail,
        String error) {

        static BackfillSnapshot idle() {
            return new BackfillSnapshot(true, true, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }

        static BackfillSnapshot failed(String error) {
            return new BackfillSnapshot(false, false, 0, 0, 0, 0, 0, 0, 0, 0, error);
        }
    }
}
