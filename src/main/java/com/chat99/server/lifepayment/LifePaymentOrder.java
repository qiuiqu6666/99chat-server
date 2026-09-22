package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.OrderStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PayMethod;
import com.chat99.server.lifepayment.LifePaymentEnums.PlatformPayStatus;
import com.chat99.server.lifepayment.LifePaymentEnums.PluginStatus;
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
@Table(name = "life_payment_orders", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_order_no", columnNames = "order_no"),
    @UniqueConstraint(name = "uk_lp_client_order", columnNames = {"user_id", "client_order_id"})
}, indexes = {
    @Index(name = "idx_lp_orders_user_created", columnList = "user_id, created_at"),
    @Index(name = "idx_lp_orders_status", columnList = "order_status, created_at"),
    @Index(name = "idx_lp_orders_service", columnList = "service_type, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "client_order_id", nullable = false, length = 128)
    private String clientOrderId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", nullable = false, length = 20)
    private PayMethod payMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_pay_status", nullable = false, length = 20)
    private PlatformPayStatus platformPayStatus = PlatformPayStatus.pending;

    @Enumerated(EnumType.STRING)
    @Column(name = "plugin_status", nullable = false, length = 40)
    private PluginStatus pluginStatus = PluginStatus.ready;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 40)
    private OrderStatus orderStatus = OrderStatus.created;

    @Column(name = "query_no", length = 64)
    private String queryNo;

    @Column(name = "paid_at")
    private Instant paidAt;

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
