package com.chat99.server.im;

import com.chat99.server.push.ConversationNotifyService;
import com.chat99.server.push.PushConfigService;
import com.chat99.server.push.PushFocusService;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GroupPushAggregationService {

    private static final Logger log = LoggerFactory.getLogger(GroupPushAggregationService.class);
    private static final String HASH_PREFIX = "push:group:agg:";
    private static final String SCHEDULE_KEY = "push:group:agg:schedule";

    private final StringRedisTemplate redis;
    private final PushConfigService pushConfig;
    private final PushService pushService;
    private final ConversationNotifyService conversationNotifyService;
    private final PushFocusService pushFocusService;
    private final ImChatPushPreviewService pushPreviewService;
    private final ObjectMapper objectMapper;
    private final ExecutorService fanoutExecutor;

    public GroupPushAggregationService(StringRedisTemplate redis,
                                       PushConfigService pushConfig,
                                       PushService pushService,
                                       ConversationNotifyService conversationNotifyService,
                                       PushFocusService pushFocusService,
                                       ImChatPushPreviewService pushPreviewService,
                                       ObjectMapper objectMapper) {
        this.redis = redis;
        this.pushConfig = pushConfig;
        this.pushService = pushService;
        this.conversationNotifyService = conversationNotifyService;
        this.pushFocusService = pushFocusService;
        this.pushPreviewService = pushPreviewService;
        this.objectMapper = objectMapper;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "group-push-fanout");
            t.setDaemon(true);
            return t;
        };
        this.fanoutExecutor = Executors.newFixedThreadPool(16, factory);
    }

    public record GroupMessageEvent(
        String groupId,
        String fromAccount,
        String senderTitle,
        String msgKey,
        String title,
        String msgBodyJson,
        String avatarUrl,
        boolean mentionAll,
        Set<String> mentionedUserIds) {

        public GroupMessageEvent {
            mentionedUserIds = mentionedUserIds == null ? Set.of() : Set.copyOf(mentionedUserIds);
        }

        boolean mentions(String userId) {
            return mentionAll || (userId != null && mentionedUserIds.contains(userId.trim()));
        }
    }

    public int enqueue(GroupMessageEvent event, List<String> memberIds) {
        if (event == null || memberIds == null || memberIds.isEmpty()) {
            return 0;
        }
        Set<String> muted = conversationNotifyService.findMutedUserIdsForGroup(event.groupId(), memberIds);
        int aggSeconds = resolveAggSeconds(memberIds.size());
        long flushAtMs = Instant.now().toEpochMilli() + aggSeconds * 1000L;
        int enqueued = 0;
        for (String memberId : memberIds) {
            if (memberId == null || memberId.isBlank()
                || memberId.equals(event.fromAccount())
                || (muted.contains(memberId) && !event.mentions(memberId))
                || pushFocusService.isFocusedOnConversation(memberId, "group", null, event.groupId())) {
                continue;
            }
            if (recordMember(event, memberId.trim(), flushAtMs, aggSeconds)) {
                enqueued++;
            }
        }
        return enqueued;
    }

    public void flushDue() {
        long now = Instant.now().toEpochMilli();
        int batch = pushConfig.getGroupPushFlushBatchSize();
        Set<String> due = redis.opsForZSet().rangeByScore(SCHEDULE_KEY, 0, now, 0, batch);
        if (due == null || due.isEmpty()) {
            return;
        }
        for (String scheduleMember : due) {
            fanoutExecutor.submit(() -> flushOne(scheduleMember));
        }
    }

    private void flushOne(String scheduleMember) {
        if (scheduleMember == null || scheduleMember.isBlank()) {
            return;
        }
        int sep = scheduleMember.indexOf('|');
        if (sep <= 0 || sep >= scheduleMember.length() - 1) {
            redis.opsForZSet().remove(SCHEDULE_KEY, scheduleMember);
            return;
        }
        String groupId = scheduleMember.substring(0, sep);
        String userId = scheduleMember.substring(sep + 1);
        String hashKey = HASH_PREFIX + groupId + ":" + userId;
        try {
            Long removed = redis.opsForZSet().remove(SCHEDULE_KEY, scheduleMember);
            if (removed == null || removed == 0L) {
                return;
            }
            var entries = redis.opsForHash().entries(hashKey);
            if (entries == null || entries.isEmpty()) {
                redis.delete(hashKey);
                return;
            }
            PushMessage message = buildAggregatedMessage(groupId, userId, entries);
            if (message != null) {
                if (pushFocusService.isFocusedOnConversation(userId, "group", null, groupId)) {
                    log.debug("im chat push skipped: push focus group={} userId={}", groupId, userId);
                    return;
                }
                pushService.sendChatToUser(userId, message, pushConfig.isChatPushSkipWhenOnline());
            }
        } catch (Exception e) {
            log.warn("group push flush failed groupId={} userId={} err={}", groupId, userId, e.getMessage());
        } finally {
            redis.delete(hashKey);
        }
    }

    private boolean recordMember(GroupMessageEvent event, String memberId, long flushAtMs, int aggSeconds) {
        String scheduleMember = event.groupId() + "|" + memberId;
        String hashKey = HASH_PREFIX + event.groupId() + ":" + memberId;
        Boolean added = redis.opsForZSet().add(SCHEDULE_KEY, scheduleMember, flushAtMs);
        if (!Boolean.TRUE.equals(added)) {
            // 窗口已存在：只合并内容，不延后 flush 时间
            mergeHash(hashKey, event);
            return false;
        }
        redis.opsForHash().put(hashKey, "count", "1");
        redis.opsForHash().put(hashKey, "senders", event.senderTitle());
        redis.opsForHash().put(hashKey, "msgBodyJson", event.msgBodyJson() == null ? "" : event.msgBodyJson());
        redis.opsForHash().put(hashKey, "lastFrom", event.fromAccount());
        redis.opsForHash().put(hashKey, "lastMsgKey", event.msgKey());
        redis.opsForHash().put(hashKey, "title", event.title());
        redis.opsForHash().put(hashKey, "avatarUrl", event.avatarUrl() == null ? "" : event.avatarUrl());
        if (aggSeconds > 0) {
            redis.expire(hashKey, java.time.Duration.ofSeconds(aggSeconds + 120L));
        }
        return true;
    }

    private void mergeHash(String hashKey, GroupMessageEvent event) {
        redis.opsForHash().increment(hashKey, "count", 1L);
        Object sendersRaw = redis.opsForHash().get(hashKey, "senders");
        String mergedSenders = mergeSenders(str(sendersRaw), event.senderTitle());
        redis.opsForHash().put(hashKey, "senders", mergedSenders);
        redis.opsForHash().put(hashKey, "msgBodyJson", event.msgBodyJson() == null ? "" : event.msgBodyJson());
        redis.opsForHash().put(hashKey, "lastFrom", event.fromAccount());
        redis.opsForHash().put(hashKey, "lastMsgKey", event.msgKey());
    }

    private PushMessage buildAggregatedMessage(String groupId, String userId,
                                               java.util.Map<Object, Object> entries) {
        String title = str(entries.get("title"));
        if (title == null || title.isBlank()) {
            title = "群消息";
        }
        int count = parseInt(str(entries.get("count")), 1);
        String senders = str(entries.get("senders"));
        String msgBodyJson = str(entries.get("msgBodyJson"));
        String lastFrom = str(entries.get("lastFrom"));
        String lastMsgKey = str(entries.get("lastMsgKey"));
        String avatarUrl = str(entries.get("avatarUrl"));
        String senderTitle = firstSenderTitle(senders);

        String lastBody = resolvePreviewBody(groupId, userId, lastFrom, senderTitle, title, msgBodyJson);
        String body;
        if (count <= 1) {
            body = lastBody == null || lastBody.isBlank() ? "新消息" : lastBody;
        } else {
            String senderLabel = formatSenders(senders, count);
            body = senderLabel + ": " + count + "条新消息";
        }

        PushMessage message = PushMessage.of(title, body)
            .withData("type", "im_chat")
            .withData("chatType", "group")
            .withData("fromAccount", lastFrom == null ? "" : lastFrom)
            .withData("groupId", groupId)
            .withData("msgKey", lastMsgKey == null ? "" : lastMsgKey)
            .withData("aggMode", count > 1 ? "merged" : "single")
            .withData("aggCount", String.valueOf(count));
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            message = message.withData("avatarUrl", avatarUrl);
        }
        return message.withApnsGrouping("group_" + groupId, "group_" + groupId);
    }

    private int resolveAggSeconds(int memberCount) {
        if (memberCount <= pushConfig.getGroupPushSmallGroupThreshold()) {
            return pushConfig.getGroupPushAggSecondsSmall();
        }
        if (memberCount <= pushConfig.getGroupPushLargeGroupThreshold()) {
            return pushConfig.getGroupPushAggSecondsMedium();
        }
        return pushConfig.getGroupPushAggSecondsLarge();
    }

    private static String mergeSenders(String existing, String senderTitle) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split("\u0001"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        }
        if (senderTitle != null && !senderTitle.isBlank()) {
            set.add(senderTitle.trim());
        }
        List<String> list = new ArrayList<>(set);
        if (list.size() > 3) {
            list = list.subList(0, 3);
        }
        return String.join("\u0001", list);
    }

    private String resolvePreviewBody(String groupId, String userId, String fromAccount,
                                      String senderTitle, String groupName, String msgBodyJson) {
        if (msgBodyJson == null || msgBodyJson.isBlank()) {
            return "新消息";
        }
        try {
            List<?> msgBody = objectMapper.readValue(msgBodyJson, new TypeReference<>() {});
            ImChatPushPreviewService.PreviewContext ctx = new ImChatPushPreviewService.PreviewContext(
                userId, "group", fromAccount, senderTitle, groupName);
            return pushPreviewService.previewGroup(ctx, msgBody).body();
        } catch (Exception e) {
            log.warn("group push preview failed groupId={} userId={} err={}", groupId, userId, e.getMessage());
            return "新消息";
        }
    }

    private static String firstSenderTitle(String senders) {
        if (senders == null || senders.isBlank()) {
            return null;
        }
        int sep = senders.indexOf('\u0001');
        if (sep < 0) {
            return senders.trim();
        }
        return senders.substring(0, sep).trim();
    }

    private static String formatSenders(String senders, int totalCount) {
        if (senders == null || senders.isBlank()) {
            return totalCount > 1 ? "群成员" : "群消息";
        }
        List<String> parts = Arrays.stream(senders.split("\u0001"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (parts.isEmpty()) {
            return "群成员";
        }
        if (parts.size() == 1 && totalCount <= 1) {
            return parts.get(0);
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return parts.get(0) + "等" + parts.size() + "人";
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
