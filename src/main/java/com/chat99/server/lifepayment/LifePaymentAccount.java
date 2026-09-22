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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "life_payment_accounts", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_account", columnNames = {"service_type", "account_no", "city_code", "provider_code"})
}, indexes = {
    @Index(name = "idx_lp_account_no", columnList = "service_type, account_no")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Column(name = "account_no", nullable = false, length = 64)
    private String accountNo;

    @Column(name = "city_name", length = 64)
    private String cityName;

    @Column(name = "city_code", length = 32)
    private String cityCode = "";

    @Column(name = "provider_name", length = 128)
    private String providerName;

    @Column(name = "provider_code", length = 64)
    private String providerCode = "";

    @Column(name = "owner_last_char", length = 8)
    private String ownerLastChar;

    @Column(name = "user_address", length = 500)
    private String userAddress;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "first_verified_at")
    private Instant firstVerifiedAt;

    @Column(name = "last_paid_at")
    private Instant lastPaidAt;

    @Column(name = "last_order_no", length = 64)
    private String lastOrderNo;

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
        if (cityCode == null) {
            cityCode = "";
        }
        if (providerCode == null) {
            providerCode = "";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (cityCode == null) {
            cityCode = "";
        }
        if (providerCode == null) {
            providerCode = "";
        }
    }
}
