package com.chat99.server.im;

import com.chat99.server.group.GroupMemberRedisSet;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import com.chat99.server.push.ConversationNotifyService;
import com.chat99.server.push.PushFocusService;
import com.chat99.server.user.PresenceService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 群推送消费。状态在 Redis Stream，不记在本机。优先流先读。 */
@Component
public class GroupPushWorker {

    private static final Logger log = LoggerFactory.getLogger(GroupPushWorker.class);

    private final StringRedisTemplate redis;
    private final GroupMemberRedisSet members;
    private final GroupMemberRepository memberRepository;
    private final GroupProfileRepository profileRepository;
    private final GroupMemberSetRepairJob repairJob;
    private final PresenceService presenceService;
    private final PushFocusService pushFocusService;
    private final ConversationNotifyService conversationNotifyService;
    private final GroupPushAggregationService aggregationService;
    private final UserRepository userRepository;
    private final int threads;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService pool;

    public GroupPushWorker(StringRedisTemplate redis,
                           GroupMemberRedisSet members,
                           GroupMemberRepository memberRepository,
                           GroupProfileRepository profileRepository,
                           GroupMemberSetRepairJob repairJob,
                           PresenceService presenceService,
                           PushFocusService pushFocusService,
                           ConversationNotifyService conversationNotifyService,
                           GroupPushAggregationService aggregationService,
                           UserRepository userRepository,
                           @Value("${chat99.im.callback.group-push-worker-threads:8}") int threads,
                           @Value("${chat99.im.callback.group-push-worker-batch-size:500}") int batchSize) {
        this.redis = redis;
        this.members = members;
        this.memberRepository = memberRepository;
        this.profileRepository = profileRepository;
        this.repairJob = repairJob;
        this.presenceService = presenceService;
        this.pushFocusService = pushFocusService;
        this.conversationNotifyService = conversationNotifyService;
        this.aggregationService = aggregationService;
        this.userRepository = userRepository;
        this.threads = threads <= 0 ? 8 : threads;
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
    }

    @PostConstruct
    void start() {
        ensureGroup(GroupPushStream.PRIORITY);
        ensureGroup(GroupPushStream.NORMAL);
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "group-push-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            String name = "worker-" + i;
            pool.execute(() -> loop(name));
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void loop(String consumerName) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                MapRecord<String, Object, Object> record = read(GroupPushStream.PRIORITY, consumerName);
                if (record == null) {
                    record = read(GroupPushStream.NORMAL, consumerName);
                }
                if (record == null) {
                    continue;
                }
                handle(record);
                redis.opsForStream().acknowledge(record.getStream(), GroupPushStream.GROUP, record.getId());
                redis.opsForStream().delete(record.getStream(), record.getId());
            } catch (RuntimeException e) {
                if (!running.get()) {
                    return;
                }
                log.warn("group push worker failed: {}", e.getMessage());
            }
        }
    }

    private MapRecord<String, Object, Object> read(String stream, String consumerName) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
            Consumer.from(GroupPushStream.GROUP, consumerName),
            StreamReadOptions.empty().count(1).block(Duration.ofMillis(200)),
            StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.get(0);
    }

    private void handle(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        String groupId = str(body.get("groupId"));
        String fromAccount = str(body.get("fromAccount"));
        if (groupId == null || fromAccount == null) {
            return;
        }
        boolean atAll = "1".equals(str(body.get("atAll")));
        Set<String> mentioned = split(str(body.get("mentioned")));
        String senderTitle = userRepository.findByUserId(fromAccount)
            .map(User::getNickname)
            .filter(n -> n != null && !n.isBlank())
            .orElse(fromAccount);
        GroupPushAggregationService.GroupMessageEvent event =
            new GroupPushAggregationService.GroupMessageEvent(
                groupId,
                fromAccount,
                senderTitle,
                str(body.get("msgKey")),
                resolveGroupTitle(groupId),
                str(body.get("msgBodyJson")),
                "",
                atAll,
                mentioned);
        String cursor = "0";
        boolean sawMember = false;
        do {
            GroupMemberRedisSet.ScanPage page = members.scan(groupId, cursor, batchSize);
            if (!page.members().isEmpty()) {
                sawMember = true;
                List<String> candidates = filter(event, page.members());
                if (!candidates.isEmpty()) {
                    aggregationService.enqueue(event, candidates);
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null && !"0".equals(cursor));
        if (!sawMember) {
            repairJob.submit(groupId);
        }
    }

    private List<String> filter(GroupPushAggregationService.GroupMessageEvent event, List<String> page) {
        Set<String> online = presenceService.likelyOnline(page);
        Set<String> focused = pushFocusService.focusedOnGroup(page, event.groupId());
        Set<String> muted = conversationNotifyService.findMutedUserIdsForGroup(event.groupId(), page);
        Set<String> mentionOnly = conversationNotifyService.findMentionOnlyUserIdsForGroup(event.groupId(), page);
        Set<String> left = findLeftMembers(event.groupId(), page);
        if (!left.isEmpty()) {
            members.removeAll(event.groupId(), left);
        }
        List<String> kept = new ArrayList<>();
        for (String memberId : page) {
            if (memberId == null || memberId.isBlank() || memberId.equals(event.fromAccount())) {
                continue;
            }
            if (left.contains(memberId)) {
                continue;
            }
            boolean mentioned = event.mentions(memberId);
            if (ConversationNotifyService.suppressGroupPush(
                muted.contains(memberId), mentionOnly.contains(memberId), mentioned)) {
                continue;
            }
            if (online.contains(memberId) && !mentioned) {
                continue;
            }
            if (focused.contains(memberId) && !mentioned) {
                continue;
            }
            kept.add(memberId);
        }
        return kept;
    }

    private Set<String> findLeftMembers(String groupId, List<String> page) {
        if (groupId == null || groupId.isBlank() || page == null || page.isEmpty()) {
            return Set.of();
        }
        List<String> ids = page.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(memberRepository.findTombstonedUserIds(groupId, ids));
    }

    private String resolveGroupTitle(String groupId) {
        return profileRepository.findById(groupId)
            .map(GroupProfile::getGroupName)
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .orElse("群消息");
    }

    private void ensureGroup(String stream) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), GroupPushStream.GROUP);
        } catch (RuntimeException e) {
            log.debug("group push stream group exists stream={} err={}", stream, e.getMessage());
        }
    }

    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
