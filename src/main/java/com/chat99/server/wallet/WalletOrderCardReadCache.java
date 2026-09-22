package com.chat99.server.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 支付成功后短时可读缓存：主从延迟 / 异步落库窗口内用 orderId 或 clientOrderId
 * 都能拿到完整卡片，避免短暂 404。
 */
@Service
public class WalletOrderCardReadCache {

    private static final Logger log = LoggerFactory.getLogger(WalletOrderCardReadCache.class);
    private static final String REDIS_PREFIX = "wallet:card:";

    private final ConcurrentHashMap<String, LocalEntry> local = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public WalletOrderCardReadCache(StringRedisTemplate redis,
                                    ObjectMapper json,
                                    @Value("${chat99.wallet.card-read-cache-ttl-seconds:60}") long ttlSeconds) {
        this.redis = redis;
        this.json = json;
        this.ttl = Duration.ofSeconds(Math.max(5L, ttlSeconds));
    }

    public void putTransfer(WalletTransfer transfer) {
        if (transfer == null || transfer.getId() == null) {
            return;
        }
        TransferSnap snap = TransferSnap.from(transfer);
        putLocal("t:id:" + snap.id(), snap, null);
        putRedis("t:id:" + snap.id(), snap);
        if (snap.clientOrderId() != null && !snap.clientOrderId().isBlank()) {
            String ck = "t:c:" + snap.clientOrderId().trim();
            putLocal(ck, snap, null);
            putRedis(ck, snap);
        }
    }

    public void putPacket(WalletRedPacket packet) {
        if (packet == null || packet.getId() == null) {
            return;
        }
        PacketSnap snap = PacketSnap.from(packet);
        putLocal("p:id:" + snap.id(), null, snap);
        putRedis("p:id:" + snap.id(), snap);
        if (snap.publicId() != null && !snap.publicId().isBlank()) {
            String ck = "p:c:" + snap.publicId().trim().toLowerCase();
            putLocal(ck, null, snap);
            putRedis(ck, snap);
        }
    }

    public void evictTransfer(WalletTransfer transfer) {
        if (transfer == null || transfer.getId() == null) {
            return;
        }
        evict("t:id:" + transfer.getId());
        if (transfer.getClientOrderId() != null && !transfer.getClientOrderId().isBlank()) {
            evict("t:c:" + transfer.getClientOrderId().trim());
        }
    }

    public void evictPacket(WalletRedPacket packet) {
        if (packet == null || packet.getId() == null) {
            return;
        }
        evict("p:id:" + packet.getId());
        if (packet.getPublicId() != null && !packet.getPublicId().isBlank()) {
            evict("p:c:" + packet.getPublicId().trim().toLowerCase());
        }
    }

    public Optional<WalletTransfer> findTransferById(long id) {
        return getTransfer("t:id:" + id);
    }

    public Optional<WalletTransfer> findTransferByClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return Optional.empty();
        }
        return getTransfer("t:c:" + clientOrderId.trim());
    }

    public Optional<WalletRedPacket> findPacketById(long id) {
        return getPacket("p:id:" + id);
    }

    public Optional<WalletRedPacket> findPacketByClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return Optional.empty();
        }
        return getPacket("p:c:" + clientOrderId.trim().toLowerCase());
    }

    private Optional<WalletTransfer> getTransfer(String key) {
        LocalEntry localHit = local.get(key);
        if (localHit != null && !localHit.expired() && localHit.transfer != null) {
            return Optional.of(localHit.transfer.toEntity());
        }
        if (localHit != null && localHit.expired()) {
            local.remove(key, localHit);
        }
        return readRedis(key, TransferSnap.class).map(TransferSnap::toEntity);
    }

    private Optional<WalletRedPacket> getPacket(String key) {
        LocalEntry localHit = local.get(key);
        if (localHit != null && !localHit.expired() && localHit.packet != null) {
            return Optional.of(localHit.packet.toEntity());
        }
        if (localHit != null && localHit.expired()) {
            local.remove(key, localHit);
        }
        return readRedis(key, PacketSnap.class).map(PacketSnap::toEntity);
    }

    private void putLocal(String key, TransferSnap transfer, PacketSnap packet) {
        local.put(key, new LocalEntry(transfer, packet, Instant.now().plus(ttl)));
    }

    private void putRedis(String key, Object value) {
        if (redis == null || json == null || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(REDIS_PREFIX + key, json.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("wallet card cache redis put skip key={}: {}", key, e.getMessage());
        }
    }

    private <T> Optional<T> readRedis(String key, Class<T> type) {
        if (redis == null || json == null) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(REDIS_PREFIX + key);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(json.readValue(raw, type));
        } catch (Exception e) {
            log.debug("wallet card cache redis get skip key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void evict(String key) {
        local.remove(key);
        if (redis == null) {
            return;
        }
        try {
            redis.delete(REDIS_PREFIX + key);
        } catch (Exception e) {
            log.debug("wallet card cache redis evict skip key={}: {}", key, e.getMessage());
        }
    }

    record TransferSnap(
        Long id,
        String clientOrderId,
        WalletTransferStatus status,
        WalletCurrency currency,
        long amount,
        long feeAmount,
        String fromUserId,
        String toUserId,
        String memo,
        Instant createdAt
    ) {
        static TransferSnap from(WalletTransfer t) {
            return new TransferSnap(
                t.getId(),
                t.getClientOrderId(),
                t.getStatus() != null ? t.getStatus() : WalletTransferStatus.PROCESSING,
                t.getCurrency(),
                t.getAmount(),
                t.getFeeAmount(),
                t.getFromUserId(),
                t.getToUserId(),
                t.getMemo(),
                t.getCreatedAt());
        }

        WalletTransfer toEntity() {
            WalletTransfer t = new WalletTransfer();
            t.setId(id);
            t.setClientOrderId(clientOrderId);
            t.setStatus(status != null ? status : WalletTransferStatus.PROCESSING);
            t.setCurrency(currency);
            t.setAmount(amount);
            t.setFeeAmount(feeAmount);
            t.setFromUserId(fromUserId);
            t.setToUserId(toUserId);
            t.setMemo(memo);
            t.setCreatedAt(createdAt);
            return t;
        }
    }

    record PacketSnap(
        Long id,
        String publicId,
        String senderUserId,
        RedPacketType packetType,
        String conversationType,
        String groupId,
        String exclusiveUserId,
        WalletCurrency currency,
        long totalAmount,
        int packetCount,
        Long perAmount,
        long remainingAmount,
        int remainingCount,
        RedPacketStatus status,
        String greeting,
        Instant expiresAt,
        Instant createdAt
    ) {
        static PacketSnap from(WalletRedPacket p) {
            return new PacketSnap(
                p.getId(),
                p.getPublicId(),
                p.getSenderUserId(),
                p.getPacketType(),
                p.getConversationType(),
                p.getGroupId(),
                p.getExclusiveUserId(),
                p.getCurrency(),
                p.getTotalAmount(),
                p.getPacketCount(),
                p.getPerAmount(),
                p.getRemainingAmount(),
                p.getRemainingCount(),
                p.getStatus() != null ? p.getStatus() : RedPacketStatus.PROCESSING,
                p.getGreeting(),
                p.getExpiresAt(),
                p.getCreatedAt());
        }

        WalletRedPacket toEntity() {
            WalletRedPacket p = new WalletRedPacket();
            p.setId(id);
            p.setPublicId(publicId);
            p.setSenderUserId(senderUserId);
            p.setPacketType(packetType);
            p.setConversationType(conversationType);
            p.setGroupId(groupId);
            p.setExclusiveUserId(exclusiveUserId);
            p.setCurrency(currency);
            p.setTotalAmount(totalAmount);
            p.setPacketCount(packetCount);
            p.setPerAmount(perAmount);
            p.setRemainingAmount(remainingAmount);
            p.setRemainingCount(remainingCount);
            p.setStatus(status != null ? status : RedPacketStatus.PROCESSING);
            p.setGreeting(greeting);
            p.setExpiresAt(expiresAt);
            p.setCreatedAt(createdAt);
            return p;
        }
    }

    private record LocalEntry(TransferSnap transfer, PacketSnap packet, Instant expiresAt) {
        boolean expired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }
}
