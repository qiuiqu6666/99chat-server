package com.chat99.server.wallet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 抢红包热路径。Lua 内同时 LPOP、HSET、XADD，抢到份额和待入账事件是一次原子操作。
 */
@Component
public class RedPacketGrabStore {

    private static final Logger log = LoggerFactory.getLogger(RedPacketGrabStore.class);
    public static final String STREAM = "rp:claim:events";
    public static final String GROUP = "rp-claim-workers";
    public static final String NOTICE_STREAM = "rp:claim:notices";
    public static final String NOTICE_GROUP = "rp-claim-notices";

    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>();

    static {
        CLAIM_SCRIPT.setResultType(List.class);
        CLAIM_SCRIPT.setScriptText("""
            local amounts = KEYS[1]
            local claims = KEYS[2]
            local meta = KEYS[3]
            local stream = KEYS[4]
            local userId = ARGV[1]
            local nowMs = tonumber(ARGV[2])
            local claimId = ARGV[3]
            local packetId = ARGV[4]
            if redis.call('EXISTS', meta) == 0 then
              return {'-3', '', '', 'NOT_READY'}
            end
            local ready = redis.call('HGET', meta, 'ready')
            if ready == '0' then
              return {'-3', '', '', 'NOT_READY'}
            end
            local expireAt = redis.call('HGET', meta, 'expireAt')
            if expireAt and expireAt ~= '' and tonumber(expireAt) > 0 and nowMs > tonumber(expireAt) then
              return {'-1', '', '', 'EXPIRED'}
            end
            local status = redis.call('HGET', meta, 'status')
            if status ~= 'ACTIVE' then
              if status == 'COMPLETED' or status == 'EMPTY' then
                return {'0', '', '', 'EMPTY'}
              end
              return {'-1', '', '', 'EXPIRED'}
            end
            local groupId = redis.call('HGET', meta, 'groupId')
            if groupId and groupId ~= '' then
              if redis.call('SISMEMBER', 'group:' .. groupId .. ':members', userId) == 0 then
                return {'-2', '', '', 'NOT_MEMBER'}
              end
            end
            local existing = redis.call('HGET', claims, userId)
            if existing then
              local bar = string.find(existing, '|', 1, true)
              local bar2 = string.find(existing, '|', bar + 1, true)
              local id = string.sub(existing, 1, bar - 1)
              local amount = string.sub(existing, bar + 1, bar2 - 1)
              local st = string.sub(existing, bar2 + 1)
              return {'2', id, amount, st}
            end
            local amount = redis.call('LPOP', amounts)
            if not amount then
              redis.call('HSET', meta, 'status', 'EMPTY')
              return {'0', '', '', 'EMPTY'}
            end
            local packed = claimId .. '|' .. amount .. '|PROCESSING'
            redis.call('HSET', claims, userId, packed)
            redis.call('XADD', stream, '*',
              'packetId', packetId,
              'userId', userId,
              'claimId', claimId,
              'amount', amount,
              'currency', redis.call('HGET', meta, 'currency'))
            local left = redis.call('LLEN', amounts)
            if left == 0 then
              redis.call('HSET', meta, 'status', 'EMPTY')
            end
            return {'1', claimId, amount, 'PROCESSING'}
            """);
    }

    private final StringRedisTemplate redis;

    public RedPacketGrabStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String amountsKey(long packetId) {
        return "rp:" + packetId + ":amounts";
    }

    public static String claimsKey(long packetId) {
        return "rp:" + packetId + ":claims";
    }

    public static String metaKey(long packetId) {
        return "rp:" + packetId + ":meta";
    }

    public boolean armed(long packetId) {
        Boolean exists = redis.hasKey(metaKey(packetId));
        return Boolean.TRUE.equals(exists);
    }

    public String groupId(long packetId) {
        Object raw = redis.opsForHash().get(metaKey(packetId), "groupId");
        return raw == null ? "" : raw.toString();
    }

    public void arm(WalletRedPacket packet, List<Long> amounts, Map<String, String> alreadyClaimed) {
        String meta = metaKey(packet.getId());
        String amountsKey = amountsKey(packet.getId());
        String claimsKey = claimsKey(packet.getId());
        redis.delete(amountsKey);
        redis.delete(claimsKey);
        if (amounts != null && !amounts.isEmpty()) {
            String[] values = amounts.stream().map(String::valueOf).toArray(String[]::new);
            redis.opsForList().rightPushAll(amountsKey, values);
        }
        if (alreadyClaimed != null && !alreadyClaimed.isEmpty()) {
            redis.opsForHash().putAll(claimsKey, alreadyClaimed);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", amounts == null || amounts.isEmpty() ? "EMPTY" : "ACTIVE");
        fields.put("senderId", packet.getSenderUserId() == null ? "" : packet.getSenderUserId());
        fields.put("groupId", packet.getGroupId() == null ? "" : packet.getGroupId());
        fields.put("currency", packet.getCurrency() == null ? "" : packet.getCurrency().name());
        fields.put("totalCount", String.valueOf(packet.getPacketCount()));
        long expireAt = packet.getExpiresAt() == null ? 0L : packet.getExpiresAt().toEpochMilli();
        fields.put("expireAt", String.valueOf(expireAt));
        fields.put("ready", "1");
        redis.opsForHash().putAll(meta, fields);
    }

    public RedPacketClaimResult claim(long packetId, String userId) {
        String claimId = String.valueOf(ClaimIds.next());
        List<?> raw = redis.execute(
            CLAIM_SCRIPT,
            List.of(amountsKey(packetId), claimsKey(packetId), metaKey(packetId), STREAM),
            userId,
            String.valueOf(Instant.now().toEpochMilli()),
            claimId,
            String.valueOf(packetId));
        return decode(raw);
    }

    public void publishNotice(long packetId, String userId, String claimId) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("packetId", String.valueOf(packetId));
        body.put("userId", userId);
        body.put("claimId", claimId);
        redis.opsForStream().add(NOTICE_STREAM, body);
    }

    public PendingClaim claimOf(long packetId, String userId) {
        Object raw = redis.opsForHash().get(claimsKey(packetId), userId);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.toString().split("\\|", 3);
        if (parts.length < 2) {
            return null;
        }
        String status = parts.length == 3 ? parts[2] : RedPacketClaimResult.PROCESSING;
        return new PendingClaim(userId, parts[0], Long.parseLong(parts[1]), status);
    }

    public void markCredited(long packetId, String userId) {
        Object raw = redis.opsForHash().get(claimsKey(packetId), userId);
        if (raw == null) {
            return;
        }
        String packed = raw.toString();
        int last = packed.lastIndexOf('|');
        if (last < 0) {
            return;
        }
        redis.opsForHash().put(claimsKey(packetId), userId, packed.substring(0, last + 1) + RedPacketClaimResult.CREDITED);
    }

    public List<PendingClaim> pending(long packetId) {
        Map<Object, Object> entries = redis.opsForHash().entries(claimsKey(packetId));
        List<PendingClaim> out = new ArrayList<>();
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            String packed = String.valueOf(e.getValue());
            String[] parts = packed.split("\\|", 3);
            if (parts.length < 2) {
                continue;
            }
            String status = parts.length == 3 ? parts[2] : RedPacketClaimResult.PROCESSING;
            out.add(new PendingClaim(String.valueOf(e.getKey()), parts[0], Long.parseLong(parts[1]), status));
        }
        return out;
    }

    public void ensureConsumerGroup() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
            local ok, err = pcall(redis.call, 'XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
            if ok then return 1 end
            if string.find(tostring(err), 'BUSYGROUP', 1, true) then return 0 end
            return redis.error_reply(tostring(err))
            """);
        try {
            redis.execute(script, List.of(STREAM), GROUP);
            redis.execute(script, List.of(NOTICE_STREAM), NOTICE_GROUP);
        } catch (RuntimeException e) {
            log.warn("claim stream group create failed: {}", e.getMessage());
        }
    }

    private static RedPacketClaimResult decode(List<?> raw) {
        if (raw == null || raw.size() < 4) {
            return new RedPacketClaimResult(false, null, null, RedPacketClaimResult.NOT_READY);
        }
        String code = String.valueOf(raw.get(0));
        String claimId = blankToNull(String.valueOf(raw.get(1)));
        String amountRaw = String.valueOf(raw.get(2));
        String status = String.valueOf(raw.get(3));
        Long amount = amountRaw.isBlank() ? null : Long.parseLong(amountRaw);
        if ("2".equals(code)) {
            return new RedPacketClaimResult(true, claimId, amount, RedPacketClaimResult.ALREADY);
        }
        boolean claimed = "1".equals(code);
        return new RedPacketClaimResult(claimed, claimId, amount, status);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record PendingClaim(String userId, String claimId, long amount, String status) {}
}
