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
@Table(name = "life_payment_providers", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_provider_code", columnNames = "provider_code"),
    @UniqueConstraint(name = "uk_lp_provider_city_name", columnNames = {"service_type", "city_name", "provider_name"})
}, indexes = {
    @Index(name = "idx_lp_provider_city", columnList = "service_type, city_name, enabled")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Column(name = "country_code", nullable = false, length = 8)
    private String countryCode = "CN";

    @Column(name = "province_name", length = 64)
    private String provinceName;

    @Column(name = "city_name", nullable = false, length = 64)
    private String cityName;

    @Column(name = "city_code", length = 32)
    private String cityCode = "";

    @Column(name = "provider_name", nullable = false, length = 128)
    private String providerName;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "provider_alias", length = 128)
    private String providerAlias;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_captured_at")
    private Instant lastCapturedAt;

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
        if (countryCode == null || countryCode.isBlank()) {
            countryCode = "CN";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (cityCode == null) {
            cityCode = "";
        }
    }
}
