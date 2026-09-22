package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskAction;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "life_payment_task_queue", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lp_task_no", columnNames = "task_no"),
    @UniqueConstraint(name = "uk_lp_active_account", columnNames = "active_account_key")
}, indexes = {
    @Index(name = "idx_lp_task_claim", columnList = "status, service_type, attempt_count, created_at"),
    @Index(name = "idx_lp_task_order", columnList = "order_no"),
    @Index(name = "idx_lp_task_query", columnList = "query_no"),
    @Index(name = "idx_lp_task_account", columnList = "service_type, account_no"),
    @Index(name = "idx_lp_task_heartbeat", columnList = "status, heartbeat_at")
})
@Getter
@Setter
@NoArgsConstructor
public class LifePaymentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_no", nullable = false, length = 64)
    private String taskNo;

    @Column(name = "order_no", length = 64)
    private String orderNo;

    @Column(name = "query_no", length = 64)
    private String queryNo;

    @Column(name = "account_no", length = 64)
    private String accountNo;

    /**
     * 仅 ready/running 的水电燃气任务持有该键；终态必须释放。
     * 利用数据库唯一约束避免并发请求绕过应用层查重。
     */
    @Column(name = "active_account_key", length = 80)
    private String activeAccountKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_action", nullable = false, length = 20)
    private TaskAction taskAction;

    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus = "none";

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.ready;

    public void setStatus(TaskStatus status) {
        this.status = status;
        if (status != TaskStatus.ready && status != TaskStatus.running) {
            activeAccountKey = null;
        }
    }

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

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
