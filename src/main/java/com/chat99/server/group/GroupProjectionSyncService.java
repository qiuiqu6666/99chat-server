package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class GroupProjectionSyncService {

    private static final Logger log = LoggerFactory.getLogger(GroupProjectionSyncService.class);
    private static final Set<String> DEFAULT_TYPES = Set.of(
        "Public", "Meeting", "Community", "Work");

    private final GroupProjectionSyncProperties props;
    private final GroupProjectionService projection;
    private final GroupProjectionTxService projectionTx;
    private final ImAdminClient im;
    private final UserRepository userRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SyncSnapshot lastSnapshot = SyncSnapshot.idle();

    public GroupProjectionSyncService(GroupProjectionSyncProperties props,
                                      GroupProjectionService projection,
                                      GroupProjectionTxService projectionTx,
                                      ImAdminClient im,
                                      UserRepository userRepository) {
        this.props = props;
        this.projection = projection;
        this.projectionTx = projectionTx;
        this.im = im;
        this.userRepository = userRepository;
    }

    public boolean isRunning() {
        return running.get();
    }

    public SyncSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public boolean startAsync(SyncRequest request) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        SyncRequest effective = request == null ? SyncRequest.defaults() : request;
        Thread worker = new Thread(() -> {
            try {
                lastSnapshot = runInternal(effective);
            } catch (Exception e) {
                log.error("group projection sync failed err={}", e.getMessage(), e);
                lastSnapshot = SyncSnapshot.failed(e.getMessage());
            } finally {
                running.set(false);
            }
        }, "group-projection-sync");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public SyncSnapshot runNow(SyncRequest request) {
        if (!running.compareAndSet(false, true)) {
            return lastSnapshot;
        }
        try {
            SyncSnapshot snapshot = runInternal(request == null ? SyncRequest.defaults() : request);
            lastSnapshot = snapshot;
            return snapshot;
        } finally {
            running.set(false);
        }
    }

    public GroupProjectionService.GroupFullSyncResult syncOneGroup(String groupId) {
        return projectionTx.syncFullGroupFromIm(groupId);
    }

    private SyncSnapshot runInternal(SyncRequest request) {
        long started = System.currentTimeMillis();
        int maxGroups = request.maxGroups() > 0 ? request.maxGroups() : props.maxGroups();
        List<String> groupIds = maxGroups > 0
            ? im.scanAppGroupIds(maxGroups)
            : im.scanAppGroupIdsDetailed(0).ids();
        int groupsScanned = 0;
        int groupsSynced = 0;
        int groupsSkipped = 0;
        int memberRows = 0;
        int usersSynced = 0;

        log.info("group projection sync start groups={} syncUsers={}", groupIds.size(), request.syncUsers());
        for (String groupId : groupIds) {
            if (groupsScanned >= maxGroups) {
                break;
            }
            groupsScanned++;
            var info = im.fetchGroupAdminInfo(groupId);
            if (info.isEmpty()) {
                groupsSkipped++;
                continue;
            }
            if (!matchesType(info.get().type(), request.groupTypes())) {
                groupsSkipped++;
                continue;
            }
            GroupProjectionService.GroupFullSyncResult result = projection.syncFullGroupFromIm(groupId);
            if (!result.synced()) {
                groupsSkipped++;
                log.debug("group projection sync skip groupId={} reason={}", groupId, result.reason());
                continue;
            }
            groupsSynced++;
            memberRows += result.memberRows();
            sleepQuiet(props.delayBetweenGroupsMs());
            if (groupsSynced % 50 == 0) {
                log.info("group projection sync progress groupsSynced={}/{} memberRows={}",
                    groupsSynced, groupIds.size(), memberRows);
            }
        }

        if (request.syncUsers()) {
            usersSynced = syncAllUsers(request.maxUsers());
        }

        long elapsedMs = System.currentTimeMillis() - started;
        SyncSnapshot snapshot = new SyncSnapshot(
            true,
            groupsScanned,
            groupsSynced,
            groupsSkipped,
            memberRows,
            usersSynced,
            elapsedMs,
            null);
        log.info("group projection sync done groupsSynced={} groupsSkipped={} memberRows={} usersSynced={} elapsedMs={}",
            groupsSynced, groupsSkipped, memberRows, usersSynced, elapsedMs);
        return snapshot;
    }

    private int syncAllUsers(int maxUsers) {
        int limit = maxUsers > 0 ? maxUsers : props.maxUsers();
        int synced = 0;
        long cursor = 0L;
        while (synced < limit) {
            int batch = Math.min(props.userBatchSize(), limit - synced);
            Page<User> page = userRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
                1, cursor, PageRequest.of(0, batch));
            if (page.isEmpty()) {
                break;
            }
            for (User user : page) {
                if (user.getUserId() == null || user.getUserId().isBlank()) {
                    continue;
                }
                try {
                    projection.syncUserMembershipsFromIm(user.getUserId());
                    synced++;
                } catch (Exception e) {
                    log.warn("group projection sync user failed userId={} err={}",
                        user.getUserId(), e.getMessage());
                }
                sleepQuiet(props.delayBetweenUsersMs());
            }
            cursor = page.getContent().get(page.getNumberOfElements() - 1).getId();
            if (page.getNumberOfElements() < batch) {
                break;
            }
        }
        return synced;
    }

    private static boolean matchesType(String groupType, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (groupType == null || groupType.isBlank()) {
            return false;
        }
        return allowed.contains(groupType.trim());
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

    public record SyncRequest(
        int maxGroups,
        boolean syncUsers,
        int maxUsers,
        Set<String> groupTypes) {

        static SyncRequest defaults() {
            return new SyncRequest(0, false, 0, DEFAULT_TYPES);
        }
    }

    public record SyncSnapshot(
        boolean completed,
        int groupsScanned,
        int groupsSynced,
        int groupsSkipped,
        int memberRows,
        int usersSynced,
        long elapsedMs,
        String error) {

        static SyncSnapshot idle() {
            return new SyncSnapshot(false, 0, 0, 0, 0, 0, 0L, null);
        }

        static SyncSnapshot failed(String error) {
            return new SyncSnapshot(false, 0, 0, 0, 0, 0, 0L, error);
        }
    }
}
