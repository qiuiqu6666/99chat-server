package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.QueryStatus;
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
@Table(name = "life_payment_utility_queries", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_query_no", columnNames = "query_no")
}, indexes = {
    @Index(name = "idx_lp_query_user", columnList = "user_id, created_at"),
    @Index(name = "idx_lp_query_account", columnList = "service_type, account_no")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentUtilityQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "query_no", nullable = false, length = 64)
    private String queryNo;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

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

    @Column(name = "suggest_amount", length = 32)
    private String suggestAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "query_status", nullable = false, length = 40)
    private QueryStatus queryStatus = QueryStatus.ready;

    @Enumerated(EnumType.STRING)
    @Column(name = "plugin_status", length = 40)
    private PluginStatus pluginStatus;

    @Column(name = "receipt", length = 500)
    private String receipt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

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
