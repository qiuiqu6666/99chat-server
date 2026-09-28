package com.chat99.server.im;

import com.chat99.server.group.GroupMemberRedisSet;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 成员集合为空时向腾讯拉全员。同一群进行中不重复投递。不在 Webhook 线程执行。 */
@Component
public class GroupMemberSetRepairJob {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberSetRepairJob.class);

    private final ImAdminClient imAdmin;
    private final GroupMemberRedisSet members;
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "group-member-repair");
        t.setDaemon(true);
        return t;
    });

    public GroupMemberSetRepairJob(ImAdminClient imAdmin, GroupMemberRedisSet members) {
        this.imAdmin = imAdmin;
        this.members = members;
    }

    public void submit(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        String id = groupId.trim();
        if (!inflight.add(id)) {
            return;
        }
        worker.execute(() -> {
            try {
                List<String> fresh = imAdmin.listGroupMemberUserIds(id, Integer.MAX_VALUE);
                if (!fresh.isEmpty()) {
                    members.addAll(id, fresh);
                }
            } catch (RuntimeException e) {
                log.warn("group member repair failed groupId={} err={}", id, e.getMessage());
            } finally {
                inflight.remove(id);
            }
        });
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
