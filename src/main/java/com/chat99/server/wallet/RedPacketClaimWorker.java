package com.chat99.server.wallet;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 多个入账消费者并行写 MySQL。腾讯通知走独立 Stream，不占入账线程。
 */
@Component
@ConditionalOnWalletJobs
public class RedPacketClaimWorker {

    private static final Logger log = LoggerFactory.getLogger(RedPacketClaimWorker.class);
    private static final int SETTLE_THREADS = 8;

    private final StringRedisTemplate redis;
    private final StringRedisTemplate claimReads;
    private final StringRedisTemplate noticeReads;
    private final TransactionTemplate tx;
    private final WalletRedPacketRepository packetRepository;
    private final EntityManager entityManager;
    private final WalletRedPacketClaimRepository claimRepository;
    private final WalletLedgerService ledgerService;
    private final RedPacketGrabStore grabStore;
    private final RedPacketClaimNoticeService claimNoticeService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Semaphore inflight = new Semaphore(SETTLE_THREADS);
    private final ExecutorService settlePool = Executors.newFixedThreadPool(SETTLE_THREADS, r -> daemon(r, "rp-claim-settle"));
    private final ExecutorService readers = Executors.newFixedThreadPool(2, r -> daemon(r, "rp-claim-read"));

    public RedPacketClaimWorker(StringRedisTemplate redis,
                                PlatformTransactionManager transactionManager,
                                WalletRedPacketRepository packetRepository,
                                EntityManager entityManager,
                                WalletRedPacketClaimRepository claimRepository,
                                WalletLedgerService ledgerService,
                                RedPacketGrabStore grabStore,
                                RedPacketClaimNoticeService claimNoticeService) {
        this.redis = redis;
        this.claimReads = blockingTemplate(redis.getConnectionFactory(), "rp-claim-block");
        this.noticeReads = blockingTemplate(redis.getConnectionFactory(), "rp-notice-block");
        this.tx = new TransactionTemplate(transactionManager);
        this.packetRepository = packetRepository;
        this.entityManager = entityManager;
        this.claimRepository = claimRepository;
        this.ledgerService = ledgerService;
        this.grabStore = grabStore;
        this.claimNoticeService = claimNoticeService;
        grabStore.ensureConsumerGroup();
        readers.execute(this::readClaims);
        readers.execute(this::readNotices);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        readers.shutdownNow();
        settlePool.shutdownNow();
    }

    private void readClaims() {
        String consumer = "wallet-claim-" + ProcessHandle.current().pid();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            List<MapRecord<String, Object, Object>> records;
            try {
                records = claimReads.opsForStream().read(
                    Consumer.from(RedPacketGrabStore.GROUP, consumer),
                    StreamReadOptions.empty().count(100).block(Duration.ofMillis(1000)),
                    StreamOffset.create(RedPacketGrabStore.STREAM, ReadOffset.lastConsumed()));
            } catch (RuntimeException e) {
                if (!running.get()) {
                    return;
                }
                log.warn("claim stream read failed: {}", e.getMessage());
                sleepBriefly();
                continue;
            }
            if (records == null || records.isEmpty()) {
                continue;
            }
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    inflight.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                settlePool.execute(() -> {
                    try {
                        settleAndAck(record);
                    } finally {
                        inflight.release();
                    }
                });
            }
        }
    }

    private void readNotices() {
        String consumer = "wallet-notice-" + ProcessHandle.current().pid();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            List<MapRecord<String, Object, Object>> records;
            try {
                records = noticeReads.opsForStream().read(
                    Consumer.from(RedPacketGrabStore.NOTICE_GROUP, consumer),
                    StreamReadOptions.empty().count(20).block(Duration.ofMillis(1000)),
                    StreamOffset.create(RedPacketGrabStore.NOTICE_STREAM, ReadOffset.lastConsumed()));
            } catch (RuntimeException e) {
                if (!running.get()) {
                    return;
                }
                log.warn("claim notice stream read failed: {}", e.getMessage());
                sleepBriefly();
                continue;
            }
            if (records == null) {
                continue;
            }
            for (MapRecord<String, Object, Object> record : records) {
                sendNotice(record);
            }
        }
    }

    void settleAndAck(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        long packetId = Long.parseLong(String.valueOf(body.get("packetId")));
        String userId = String.valueOf(body.get("userId"));
        String claimId = String.valueOf(body.get("claimId"));
        long amount = Long.parseLong(String.valueOf(body.get("amount")));
        boolean settled = Boolean.TRUE.equals(tx.execute(status -> settle(packetId, userId, claimId, amount)));
        if (!settled) {
            return;
        }
        try {
            grabStore.publishNotice(packetId, userId, claimId);
        } catch (RuntimeException e) {
            log.warn("claim notice enqueue failed packetId={}: {}", packetId, e.getMessage());
            return;
        }
        redis.opsForStream().acknowledge(RedPacketGrabStore.STREAM, RedPacketGrabStore.GROUP, record.getId());
        grabStore.markCredited(packetId, userId);
    }

    private void sendNotice(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        long packetId = Long.parseLong(String.valueOf(body.get("packetId")));
        String userId = String.valueOf(body.get("userId"));
        try {
            packetRepository.findById(packetId).ifPresent(packet ->
                claimRepository.findByPacketIdAndUserId(packetId, userId).ifPresent(claim ->
                    claimNoticeService.sendNotice(packet, claim)));
            noticeReads.opsForStream().acknowledge(
                RedPacketGrabStore.NOTICE_STREAM, RedPacketGrabStore.NOTICE_GROUP, record.getId());
        } catch (RuntimeException e) {
            log.warn("claim notice send failed packetId={}: {}", packetId, e.getMessage());
        }
    }

    /**
     * @return true 当本条可以 ACK（新入账或唯一键已存在）
     */
    boolean settle(long packetId, String userId, String claimId, long amount) {
        if (claimRepository.existsByPacketIdAndUserId(packetId, userId)) {
            return true;
        }
        WalletRedPacket packet = packetRepository.findById(packetId).orElse(null);
        if (packet == null || amount <= 0) {
            return true;
        }
        WalletRedPacketClaim claim = new WalletRedPacketClaim();
        claim.setId(parseClaimId(claimId));
        claim.setPacketId(packetId);
        claim.setUserId(userId);
        claim.setAmount(amount);
        try {
            claimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            return true;
        }
        ledgerService.credit(userId, packet.getCurrency(), amount, WalletLedgerType.RED_PACKET_RECEIVE,
            "RED_PACKET_CLAIM", packetId, packet.getSenderUserId(), claimId);
        entityManager.createNativeQuery("""
            UPDATE wallet_red_packet
            SET remaining_count = GREATEST(remaining_count - 1, 0),
                remaining_amount = GREATEST(remaining_amount - :amount, 0),
                status = CASE WHEN remaining_count = 0 THEN 'COMPLETED' ELSE status END
            WHERE id = :id
            """)
            .setParameter("amount", amount)
            .setParameter("id", packetId)
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return true;
    }

    private static long parseClaimId(String claimId) {
        try {
            return Long.parseLong(claimId);
        } catch (RuntimeException e) {
            return ClaimIds.next();
        }
    }

    private static StringRedisTemplate blockingTemplate(RedisConnectionFactory source, String name) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory();
        if (source instanceof LettuceConnectionFactory lettuce) {
            factory = new LettuceConnectionFactory(lettuce.getHostName(), lettuce.getPort());
            factory.setDatabase(lettuce.getDatabase());
            if (lettuce.getPassword() != null) {
                factory.setPassword(lettuce.getPassword());
            }
        }
        factory.setShareNativeConnection(true);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        log.info("red packet blocking redis ready name={}", name);
        return template;
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void sleepBriefly() {
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
