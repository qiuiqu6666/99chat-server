package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.WorkerStatus;
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
@Table(name = "life_payment_worker_devices", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_worker_id", columnNames = "worker_id")
}, indexes = {
    @Index(name = "idx_lp_worker_status", columnList = "status, last_heartbeat_at"),
    @Index(name = "idx_lp_worker_token_hash", columnList = "worker_token_hash", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentWorkerDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "worker_id", nullable = false, length = 64)
    private String workerId;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "device_name", length = 128)
    private String deviceName;

    @Column(name = "support_service_types", nullable = false, length = 200)
    private String supportServiceTypes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkerStatus status = WorkerStatus.offline;

    @Column(name = "last_online_at")
    private Instant lastOnlineAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_offline_at")
    private Instant lastOfflineAt;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "worker_token_hash", length = 64)
    private String workerTokenHash;

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
