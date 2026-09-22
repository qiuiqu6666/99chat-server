package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_fee_config",
    uniqueConstraints = @UniqueConstraint(name = "uk_fee_scene_currency", columnNames = {"scene", "currency"}))
@Getter
@Setter
@NoArgsConstructor
public class WalletFeeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene", nullable = false, length = 32)
    private WalletFeeScene scene;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 16)
    private WalletCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false, length = 16)
    private WalletFeeType feeType;

    @Column(name = "fee_value", nullable = false)
    private long feeValue;

    @Column(name = "min_fee")
    private Long minFee;

    @Column(name = "max_fee")
    private Long maxFee;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
