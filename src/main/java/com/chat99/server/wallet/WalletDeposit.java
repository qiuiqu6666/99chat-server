package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_deposit",
    uniqueConstraints = @UniqueConstraint(name = "uk_deposit_tx", columnNames = {"tx_id", "log_index"}),
    indexes = {
        @Index(name = "idx_deposit_user", columnList = "user_id"),
        @Index(name = "idx_deposit_status", columnList = "status")
    })
@Getter
@Setter
@NoArgsConstructor
public class WalletDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "tx_id", nullable = false, length = 64)
    private String txId;

    @Column(name = "log_index", nullable = false)
    private int logIndex;

    @Column(name = "from_address", length = 34)
    private String fromAddress;

    @Column(name = "to_address", nullable = false, length = 34)
    private String toAddress;

    @Column(name = "amount_micro", nullable = false)
    private long amountMicro;

    @Column(name = "confirmations", nullable = false)
    private int confirmations;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DepositStatus status;

    @Column(name = "block_timestamp")
    private Instant blockTimestamp;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
