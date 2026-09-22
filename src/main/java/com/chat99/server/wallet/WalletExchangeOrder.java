package com.chat99.server.wallet;

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
@Table(name = "wallet_exchange_order")
@Getter
@Setter
@NoArgsConstructor
public class WalletExchangeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private ExchangeDirection direction;

    @Column(name = "input_amount", nullable = false)
    private long inputAmount;

    @Column(name = "output_amount", nullable = false)
    private long outputAmount;

    @Column(name = "surplus_fen", nullable = false)
    private long surplusFen;

    @Column(name = "rate_snapshot", length = 512)
    private String rateSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
