package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_withdraw_live_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_wd_live_activity_order", columnNames = "withdrawal_id"))
@Getter
@Setter
@NoArgsConstructor
public class WalletWithdrawLiveActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "withdrawal_id", nullable = false)
    private long withdrawalId;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "activity_id", nullable = false, length = 128)
    private String activityId;

    @Column(name = "push_token", nullable = false, length = 512)
    private String pushToken;

    @Column(name = "bundle_id", length = 128)
    private String bundleId;

    @Column(name = "environment", length = 32)
    private String environment;

    @Column(name = "last_push_at")
    private Instant lastPushAt;

    @Column(name = "last_push_stage", length = 16)
    private String lastPushStage;

    @Column(name = "last_push_confirmations", nullable = false)
    private int lastPushConfirmations;

    @Column(name = "last_push_event", length = 8)
    private String lastPushEvent;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
