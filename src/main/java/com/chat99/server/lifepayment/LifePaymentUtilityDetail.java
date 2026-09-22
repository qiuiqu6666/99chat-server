package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "life_payment_utility_details", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_utility_order", columnNames = "order_no")
}, indexes = {
    @Index(name = "idx_lp_utility_account", columnList = "service_type, account_no")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentUtilityDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Column(name = "city_name", nullable = false, length = 64)
    private String cityName;

    @Column(name = "city_code", length = 32)
    private String cityCode;

    @Column(name = "provider_name", nullable = false, length = 128)
    private String providerName;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "account_no", nullable = false, length = 64)
    private String accountNo;

    @Column(name = "user_address", length = 500)
    private String userAddress;

    @Column(name = "account_balance", length = 128)
    private String accountBalance;

    @Column(name = "utility_status", length = 64)
    private String utilityStatus;

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
