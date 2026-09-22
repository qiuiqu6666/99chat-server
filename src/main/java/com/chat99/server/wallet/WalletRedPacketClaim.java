package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_red_packet_claim",
    uniqueConstraints = @UniqueConstraint(name = "uk_claim_packet_user", columnNames = {"packet_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WalletRedPacketClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "packet_id", nullable = false)
    private long packetId;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
