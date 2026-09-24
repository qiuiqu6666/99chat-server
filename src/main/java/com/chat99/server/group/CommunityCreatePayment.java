package com.chat99.server.group;

import com.chat99.server.wallet.WalletCurrency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "community_create_payment")
@Getter
@Setter
@NoArgsConstructor
public class CommunityCreatePayment {
    @Id
    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Column(name = "owner_user_id", nullable = false, length = 32)
    private String ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 16)
    private WalletCurrency currency;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
