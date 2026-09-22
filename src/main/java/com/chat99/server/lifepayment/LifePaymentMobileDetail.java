package com.chat99.server.lifepayment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "life_payment_mobile_details", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_mobile_order", columnNames = "order_no")
}, indexes = {
    @Index(name = "idx_lp_mobile_phone", columnList = "phone")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentMobileDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "owner_last_char", length = 8)
    private String ownerLastChar;

    @Column(name = "is_first_recharge", nullable = false)
    private boolean firstRecharge;

    @Column(name = "carrier_name", length = 64)
    private String carrierName;

    @Column(name = "recharge_status", length = 64)
    private String rechargeStatus;

    @Column(name = "receipt", length = 500)
    private String receipt;

    @Column(name = "alipay_trade_no", length = 128)
    private String alipayTradeNo;

    @Column(name = "paid_amount", precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
