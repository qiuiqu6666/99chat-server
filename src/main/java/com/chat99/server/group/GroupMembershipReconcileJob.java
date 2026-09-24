package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.restqueue.ImRestQueuePublisher;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫本地人数分叉与社群 IM MemberNum，入队全群对账。吃 REST 队列限流，不阻塞进出群。
 */
@Component
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class GroupMembershipReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(GroupMembershipReconcileJob.class);
    private static final String COMMUNITY_PREFIX = "@TGS#_";
    private static final int COMMUNITY_SAMPLE = 5;

    private final GroupProfileRepository profileRepository;
    private final GroupMemberRepository memberRepository;
    private final ImAdminClient im;
    private final ObjectProvider<ImRestQueuePublisher> queuePublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${chat99.im.membership-reconcile-max-groups:20}")
    private int maxGroups;

    public GroupMembershipReconcileJob(GroupProfileRepository profileRepository,
                                       GroupMemberRepository memberRepository,
                                       ImAdminClient im,
                                       ObjectProvider<ImRestQueuePublisher> queuePublisher) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
        this.im = im;
        this.queuePublisher = queuePublisher;
    }

    @Scheduled(cron = "${chat99.im.membership-reconcile-cron:0 */10 * * * ?}")
    public void enqueueMismatchedGroups() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            ImRestQueuePublisher publisher = queuePublisher.getIfAvailable();
            if (publisher == null) {
                return;
            }
            int cap = Math.max(1, maxGroups);
            LinkedHashSet<String> queued = new LinkedHashSet<>();
            List<String> mismatched =
                profileRepository.findGroupIdsWithMemberCountMismatch(PageRequest.of(0, cap));
            for (String groupId : mismatched) {
                if (groupId == null || groupId.isBlank() || queued.size() >= cap) {
                    continue;
                }
                publisher.enqueueReconcileGroupMembers(groupId.trim(), "scheduled_count_mismatch");
                queued.add(groupId.trim());
            }
            int remaining = cap - queued.size();
            int communityChecked = 0;
            if (remaining > 0) {
                Page<GroupProfile> communities = profileRepository
                    .findByDismissedFalseAndGroupIdStartingWithOrderByUpdatedAtDesc(
                        COMMUNITY_PREFIX, PageRequest.of(0, COMMUNITY_SAMPLE));
                for (GroupProfile profile : communities) {
                    if (queued.size() >= cap) {
                        break;
                    }
                    String groupId = profile.getGroupId();
                    if (groupId == null || queued.contains(groupId)) {
                        continue;
                    }
                    communityChecked++;
                    int local = (int) memberRepository.countActiveByGroupId(groupId);
                    int imNum = Math.max(im.countGroupMembers(groupId), 0);
                    if (local != imNum || local != profile.getMemberCount()) {
                        publisher.enqueueReconcileGroupMembers(groupId, "scheduled_community_im");
                        queued.add(groupId);
                    }
                }
            }
            log.info("membership reconcile scheduled enqueued={} mismatchCandidates={} communityChecked={}",
                queued.size(), mismatched.size(), communityChecked);
        } finally {
            running.set(false);
        }
    }
}
