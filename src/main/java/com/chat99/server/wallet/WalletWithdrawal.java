package com.chat99.server.wallet;

import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_withdrawal",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_withdrawal_client_order", columnNames = {"user_id", "client_order_id"}),
    indexes = @Index(name = "idx_withdrawal_client_order", columnList = "client_order_id"))
@Getter
@Setter
@NoArgsConstructor
public class WalletWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "to_address", nullable = false, length = 34)
    private String toAddress;

    @Column(name = "amount_micro", nullable = false)
    private long amountMicro;

    @Column(name = "fee_micro", nullable = false)
    private long feeMicro;

    @Column(name = "payout_micro", nullable = false)
    private long payoutMicro;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WithdrawalStatus status;

    @Column(name = "tx_id", length = 64)
    private String txId;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    /** 客户端幂等 ID；与 user_id 联合唯一。 */
    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    @Column(name = "confirmations", nullable = false)
    private int confirmations;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public WithdrawStage getStage() {
        return WithdrawStage.fromStatus(status);
    }
}

