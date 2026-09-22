package com.chat99.server.wallet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_wallet")
@Getter
@Setter
@NoArgsConstructor
public class UserWallet {

    @Id
    @Column(name = "user_id", length = 10)
    private String userId;

    @Column(name = "tron_address", nullable = false, length = 34, unique = true)
    private String tronAddress;

    @JsonIgnore
    @Column(name = "tron_private_key", length = 512)
    private String tronPrivateKey;

    @Column(name = "derivation_index", nullable = false, unique = true)
    private long derivationIndex;

    @Column(name = "balance_usdt_micro", nullable = false)
    private long balanceUsdtMicro;

    @Column(name = "balance_platform_fen", nullable = false)
    private long balancePlatformFen;

    @Column(name = "balance_trx_sun", nullable = false)
    private long balanceTrxSun;

    @Column(name = "chain_usdt_micro", nullable = false)
    private long chainUsdtMicro;

    @Column(name = "chain_trx_sun", nullable = false)
    private long chainTrxSun;

    @Column(name = "chain_balance_at")
    private Instant chainBalanceAt;

    @Column(name = "pay_pin_hash", length = 100)
    private String payPinHash;

    @Column(name = "pay_pin_fail_count", nullable = false)
    private int payPinFailCount;

    @Column(name = "pay_pin_locked_until")
    private Instant payPinLockedUntil;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
