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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_sweep_log", indexes = {
    @Index(name = "idx_sweep_log_user_time", columnList = "user_id,created_at"),
    @Index(name = "idx_sweep_log_status_time", columnList = "status,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class WalletSweepLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "from_address", nullable = false, length = 34)
    private String fromAddress;

    @Column(name = "hot_wallet_address", length = 34)
    private String hotWalletAddress;

    @Column(name = "usdt_swept_micro", nullable = false)
    private long usdtSweptMicro;

    @Column(name = "trx_swept_sun", nullable = false)
    private long trxSweptSun;

    @Column(name = "usdt_tx_id", length = 64)
    private String usdtTxId;

    @Column(name = "trx_tx_id", length = 64)
    private String trxTxId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WalletSweepStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private WalletSweepTrigger triggerType;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "fail_reason", length = 512)
    private String failReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
