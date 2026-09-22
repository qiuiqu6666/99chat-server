package com.chat99.server.im.restqueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class ImRestQueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(ImRestQueuePublisher.class);
    static final String DEDUPE_PREFIX = "im:restq:dedupe:";

    private final ImRestQueueProperties props;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;

    public ImRestQueuePublisher(ImRestQueueProperties props,
                                StringRedisTemplate redis,
                                @Qualifier("imRestQueueKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper json) {
        this.props = props;
        this.redis = redis;
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    public void enqueueRefreshRole(String groupId, String userId, String reason) {
        enqueue(baseJob(ImRestJob.Type.REFRESH_ROLE, trim(groupId), trim(userId), reason));
    }

    public void enqueueHydrateGroup(String groupId, String userId, String reason) {
        enqueue(baseJob(ImRestJob.Type.HYDRATE_GROUP, trim(groupId), trim(userId), reason));
    }

    public void enqueueSyncUserJoined(String userId, String reason) {
        enqueue(baseJob(ImRestJob.Type.SYNC_USER_JOINED, null, trim(userId), reason));
    }

    public void enqueueVerifyGroupExists(String groupId, String reason) {
        enqueue(baseJob(ImRestJob.Type.VERIFY_GROUP_EXISTS, trim(groupId), null, reason));
    }

    public void enqueueFriendAddBoth(String userId, String peerUserId, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.FRIEND_ADD_BOTH, null, trim(userId), null, 0,
            Instant.now().toEpochMilli(), reason, trim(peerUserId), null, null, null));
    }

    public void enqueueFriendDeleteBoth(String userId, String peerUserId, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.FRIEND_DELETE_BOTH, null, trim(userId), null, 0,
            Instant.now().toEpochMilli(), reason, trim(peerUserId), null, null, null));
    }

    public void enqueueFriendRemarkUpdate(String userId, String peerUserId, String remark, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.FRIEND_REMARK_UPDATE, null, trim(userId), null, 0,
            Instant.now().toEpochMilli(), reason, trim(peerUserId),
            remark == null ? "" : remark, null, null));
    }

    public void enqueueGroupModifyBaseInfo(String groupId, String stringValue, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.GROUP_MODIFY_BASE_INFO, trim(groupId), null, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, null, stringValue));
    }

    public void enqueueGroupModifyFaceUrl(String groupId, String faceUrl, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.GROUP_MODIFY_FACE_URL, trim(groupId), null, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, null, faceUrl == null ? "" : faceUrl));
    }

    public void enqueueGroupModifyJoinOptions(String groupId, String stringValue, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.GROUP_MODIFY_JOIN_OPTIONS, trim(groupId), null, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, null, stringValue));
    }

    public void enqueueGroupAddMembers(String groupId, List<String> memberUserIds, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.GROUP_ADD_MEMBERS, trim(groupId), null, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, copyMembers(memberUserIds), null));
    }

    public void enqueueGroupDeleteMembers(String groupId, List<String> memberUserIds, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.GROUP_DELETE_MEMBERS, trim(groupId), null, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, copyMembers(memberUserIds), null));
    }

    public void enqueueGroupDestroy(String groupId, String reason) {
        enqueue(new ImRestJob(
            newJobId(), ImRestJob.Type.GROUP_DESTROY, trim(groupId), null, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, null, null));
    }

    public void enqueue(ImRestJob job) {
        enqueue(job, true);
    }

    /** 重试入队跳过去重，避免 attempt 被 dedupe 吃掉。 */
    public boolean enqueueRetry(ImRestJob job) {
        return enqueue(job, false);
    }

    private boolean enqueue(ImRestJob job, boolean dedupe) {
        if (job == null || job.type() == null) {
            return false;
        }
        if (dedupe) {
            String dedupeKey = dedupeKey(job);
            if (!tryDedupe(dedupeKey)) {
                log.debug("im rest job deduped type={} key={}", job.type(), dedupeKey);
                return false;
            }
        }
        try {
            String payload = json.writeValueAsString(job);
            String key = partitionKey(job);
            ProducerRecord<String, String> record = new ProducerRecord<>(props.topic(), key, payload);
            record.headers().add(new RecordHeader(
                "X-Job-Type", job.type().name().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
            log.debug("im rest job enqueued type={} key={} reason={} attempt={}",
                job.type(), key, job.reason(), job.attempt());
            return true;
        } catch (JsonProcessingException e) {
            log.warn("im rest job serialize failed type={} err={}", job.type(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("im rest job publish interrupted type={} err={}", job.type(), e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.warn("im rest job publish failed type={} err={}", job.type(), e.getMessage());
        } catch (Exception e) {
            log.warn("im rest job publish failed type={} err={}", job.type(), e.getMessage());
        }
        return false;
    }

    public boolean publishToDlq(ImRestJob job, String error) {
        if (job == null) {
            return false;
        }
        try {
            String payload = json.writeValueAsString(job);
            ProducerRecord<String, String> record =
                new ProducerRecord<>(props.topicDlq(), partitionKey(job), payload);
            if (error != null) {
                record.headers().add(new RecordHeader(
                    "X-Error", error.getBytes(StandardCharsets.UTF_8)));
            }
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
            return true;
        } catch (JsonProcessingException e) {
            log.warn("im rest dlq serialize failed type={} err={}", job.type(), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("im rest dlq publish interrupted type={} err={}", job.type(), e.getMessage());
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("im rest dlq publish failed type={} err={}", job.type(), e.getMessage());
            return false;
        }
    }

    private boolean tryDedupe(String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return true;
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                DEDUPE_PREFIX + dedupeKey,
                "1",
                Duration.ofSeconds(props.dedupeTtlSeconds()));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("im rest dedupe failed key={} err={}", dedupeKey, e.getMessage());
            return true;
        }
    }

    private static String dedupeKey(ImRestJob job) {
        return switch (job.type()) {
            case REFRESH_ROLE -> "REFRESH_ROLE:" + nullToEmpty(job.groupId()) + ":" + nullToEmpty(job.userId());
            case HYDRATE_GROUP -> "HYDRATE_GROUP:" + nullToEmpty(job.groupId()) + ":" + nullToEmpty(job.userId());
            case SYNC_USER_JOINED -> "SYNC_USER_JOINED:" + nullToEmpty(job.userId());
            case VERIFY_GROUP_EXISTS -> "VERIFY_GROUP_EXISTS:" + nullToEmpty(job.groupId());
            case FRIEND_ADD_BOTH, FRIEND_DELETE_BOTH ->
                job.type().name() + ":" + pairKey(job.userId(), job.peerUserId());
            case FRIEND_REMARK_UPDATE ->
                job.type().name() + ":" + pairKey(job.userId(), job.peerUserId())
                    + ":" + nullToEmpty(job.userId());
            case GROUP_MODIFY_BASE_INFO, GROUP_MODIFY_FACE_URL, GROUP_MODIFY_JOIN_OPTIONS, GROUP_DESTROY ->
                job.type().name() + ":" + nullToEmpty(job.groupId());
            case GROUP_ADD_MEMBERS, GROUP_DELETE_MEMBERS ->
                job.type().name() + ":" + nullToEmpty(job.groupId()) + ":" + membersKey(job.memberUserIds());
        };
    }

    private static String partitionKey(ImRestJob job) {
        return switch (job.type()) {
            case REFRESH_ROLE, HYDRATE_GROUP, VERIFY_GROUP_EXISTS,
                 GROUP_MODIFY_BASE_INFO, GROUP_MODIFY_FACE_URL, GROUP_MODIFY_JOIN_OPTIONS,
                 GROUP_ADD_MEMBERS, GROUP_DELETE_MEMBERS, GROUP_DESTROY ->
                job.type().name() + ":" + nullToEmpty(job.groupId());
            case SYNC_USER_JOINED -> job.type().name() + ":" + nullToEmpty(job.userId());
            case FRIEND_ADD_BOTH, FRIEND_DELETE_BOTH ->
                job.type().name() + ":" + pairKey(job.userId(), job.peerUserId());
            case FRIEND_REMARK_UPDATE ->
                job.type().name() + ":" + pairKey(job.userId(), job.peerUserId())
                    + ":" + nullToEmpty(job.userId());
        };
    }

    private ImRestJob baseJob(ImRestJob.Type type, String groupId, String userId, String reason) {
        return new ImRestJob(
            newJobId(), type, groupId, userId, null, 0,
            Instant.now().toEpochMilli(), reason, null, null, null, null);
    }

    private static List<String> copyMembers(List<String> memberUserIds) {
        if (memberUserIds == null || memberUserIds.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : memberUserIds) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }

    private static String membersKey(List<String> memberUserIds) {
        if (memberUserIds == null || memberUserIds.isEmpty()) {
            return "";
        }
        return memberUserIds.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .sorted()
            .collect(Collectors.joining(","));
    }

    private static String pairKey(String a, String b) {
        String left = nullToEmpty(a);
        String right = nullToEmpty(b);
        if (left.compareTo(right) <= 0) {
            return left + ":" + right;
        }
        return right + ":" + left;
    }

    private static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
