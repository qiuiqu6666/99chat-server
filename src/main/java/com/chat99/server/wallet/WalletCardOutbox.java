package com.chat99.server.wallet;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Created in the money transaction. Never contains the payment PIN. */
@Entity
@Table(name = "wallet_card_outbox", uniqueConstraints = @UniqueConstraint(
    name = "uk_wallet_card_client", columnNames = {"sender_id", "client_id"}),
    indexes = {@Index(name = "idx_wallet_card_due", columnList = "state,next_attempt_at"),
        @Index(name = "idx_wallet_card_sync", columnList = "state,next_sync_at")})
@Getter
@Setter
public class WalletCardOutbox {
    @Id @Column(length = 80) private String id;
    @Column(name = "sender_id", nullable = false, length = 64) private String senderId;
    @Column(name = "client_id", nullable = false, length = 64) private String clientId;
    @Column(name = "target_id", nullable = false, length = 128) private String targetId;
    @Column(name = "group_message", nullable = false) private boolean groupMessage;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(name = "im_random", nullable = false) private int imRandom;
    @Column(nullable = false, length = 16) private String state = "PENDING";
    @Column(nullable = false) private int attempts;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "first_attempt_at") private Instant firstAttemptAt;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt = Instant.now();
    @Column(name = "lease_token", length = 36) private String leaseToken;
    @Column(name = "message_key", length = 128) private String messageKey;
    @Column(name = "message_seq") private Long messageSeq;
    @Column(name = "next_sync_at") private Instant nextSyncAt = Instant.now();
    @Column(name = "last_sync_at") private Instant lastSyncAt;
    @Column(name = "synced_payload", columnDefinition = "TEXT") private String syncedPayload;
    @Column(name = "sync_error", length = 64) private String syncError;
    @Column(name = "last_error", length = 64) private String lastError;
    @Column(name = "reconcile_cursor", length = 256) private String reconcileCursor;
}
