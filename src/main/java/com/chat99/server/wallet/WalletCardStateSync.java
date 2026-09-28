package com.chat99.server.wallet;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles committed money state, including claims/expiry missed during a restart. */
@Service
public class WalletCardStateSync {
    private static final Logger log = LoggerFactory.getLogger(WalletCardStateSync.class);
    private final WalletCardOutboxRepository outbox;
    private final WalletRedPacketRepository packets;
    private final WalletRedPacketClaimRepository claims;
    private final ImAdminClient im;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public WalletCardStateSync(WalletCardOutboxRepository outbox, WalletRedPacketRepository packets,
        WalletRedPacketClaimRepository claims,
        ImAdminClient im, ObjectMapper json, PlatformTransactionManager manager) {
        this.outbox = outbox; this.packets = packets; this.claims = claims; this.im = im; this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    @Scheduled(scheduler = "walletCardStateScheduler", initialDelay = 7000,
        fixedDelayString = "${wallet.card-state.poll-ms:2000}")
    public void run() {
        try {
            for (String id : outbox.syncDue(Instant.now(), PageRequest.of(0, 64))) {
                try { sync(id); }
                catch (RuntimeException e) { log.warn("wallet card state worker failed id={}", id, e); }
            }
        } catch (RuntimeException e) { log.warn("wallet card state poll failed", e); }
    }

    public void sync(String id) {
        // Lock ONLY the outbox row through the bounded network call. Unlike an
        // expiring lease, another instance cannot overtake a paused old worker.
        // Money/claim rows remain unlocked and no network call runs in a money transaction.
        tx.executeWithoutResult(transaction -> {
            var row = outbox.lock(id).orElse(null);
            Instant now = Instant.now();
            if (row == null || !"SENT".equals(row.getState()) || !id.startsWith("rp:")
                || row.getNextSyncAt() == null || row.getNextSyncAt().isAfter(now)) return;
            row.setNextSyncAt(now.plusSeconds(30));
            if (row.isGroupMessage() ? row.getMessageSeq() == null || row.getMessageSeq() <= 0
                : row.getMessageKey() == null || row.getMessageKey().isBlank()) {
                row.setSyncError("MESSAGE_LOCATOR_MISSING"); return;
            }
            var packet = packets.findById(Long.parseLong(id.substring(3))).orElse(null);
            if (packet == null) { row.setSyncError("ORDER_NOT_FOUND"); return; }
            try {
                // Refund clears remaining_count; it must not make every share look claimed.
                long claimed = packet.getStatus() == RedPacketStatus.REFUNDED
                    || packet.getStatus() == RedPacketStatus.EXPIRED
                    ? claims.countByPacketId(packet.getId())
                    : Math.max(0, packet.getPacketCount() - packet.getRemainingCount());
                String payload = snapshot(row.getPayload(), packet, claimed, json);
                // Periodic repair also corrects a delayed remote write after an unknown timeout.
                boolean audit = row.getLastSyncAt() == null || !now.isBefore(row.getLastSyncAt().plusSeconds(300));
                boolean alreadyApplied = payload.equals(row.getSyncedPayload());
                if (!payload.equals(row.getSyncedPayload()) || audit) {
                    im.updateWalletCard(row.isGroupMessage(), row.getSenderId(), row.getTargetId(),
                        row.getMessageKey(), row.getMessageSeq(), payload);
                    row.setSyncedPayload(payload); row.setLastSyncAt(Instant.now());
                }
                row.setSyncError(null);
                boolean terminal = packet.getStatus() == RedPacketStatus.COMPLETED
                    || packet.getStatus() == RedPacketStatus.EXPIRED || packet.getStatus() == RedPacketStatus.REFUNDED;
                // One delayed terminal repair, then retire the task instead of
                // polling every historical red packet forever.
                row.setNextSyncAt(terminal && alreadyApplied && audit ? null
                    : Instant.now().plusSeconds(terminal ? 300 : 5));
            } catch (ImRestException e) {
                row.setSyncError("IM_" + e.imErrorCode());
                log.warn("wallet card state retry id={} code={}", id, e.imErrorCode());
            } catch (RuntimeException e) {
                row.setSyncError("SYNC_FAILED");
                log.warn("wallet card state retry id={}", id, e);
            }
        });
    }

    static String snapshot(String original, WalletRedPacket p, long claimed, ObjectMapper json) {
        try {
            Map<String, Object> data = new LinkedHashMap<>(json.readValue(original,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            boolean grab = p.getPacketType() == RedPacketType.NORMAL_GROUP || p.getPacketType() == RedPacketType.LUCKY_GROUP;
            String state = switch (p.getStatus()) {
                case COMPLETED -> grab ? "empty" : "success";
                case EXPIRED -> "expired";
                case REFUNDED -> "refunded";
                default -> "active";
            };
            data.put("status", state);
            data.put("remainingCount", p.getRemainingCount());
            data.put("remainingAmount", p.getRemainingAmount());
            data.put("claimedCount", claimed);
            data.put("packetStatus", p.getStatus().name());
            if (p.getExpiresAt() != null) data.put("expiresAt", p.getExpiresAt().toString());
            // A revision of display state, not permission or proof of payment.
            data.put("cardStateVersion", (long) Math.max(0, p.getPacketCount() - p.getRemainingCount()) * 10
                + (p.getStatus() == RedPacketStatus.EXPIRED || p.getStatus() == RedPacketStatus.REFUNDED ? 2
                    : p.getStatus() == RedPacketStatus.COMPLETED ? 1 : 0));
            return json.writeValueAsString(data);
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid wallet card snapshot", e); }
    }
}
