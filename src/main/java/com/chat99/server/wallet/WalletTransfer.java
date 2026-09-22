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
@Table(name = "wallet_transfer",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_transfer_client_order", columnNames = {"from_user_id", "client_order_id"}),
    indexes = @Index(name = "idx_transfer_client_order", columnList = "client_order_id"))
@Getter
@Setter
@NoArgsConstructor
public class WalletTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_user_id", nullable = false, length = 10)
    private String fromUserId;

    @Column(name = "to_user_id", nullable = false, length = 10)
    private String toUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 16)
    private WalletCurrency currency;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "memo", length = 128)
    private String memo;

    /** 客户端幂等 ID；与 from_user_id 联合唯一。 */
    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WalletTransferStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = WalletTransferStatus.COMPLETED;
    }
}
