package com.chat99.server.adminapi;

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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "admin_user_generation_tasks", indexes = {
    @Index(name = "idx_augt_status_created", columnList = "status, created_at"),
    @Index(name = "idx_augt_created_by", columnList = "created_by, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AdminUserGenerationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_no", nullable = false, unique = true, length = 64)
    private String taskNo;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "requested_count", nullable = false)
    private int requestedCount;

    @Column(name = "processed_count", nullable = false)
    private int processedCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AdminUserGenerationStatus status = AdminUserGenerationStatus.pending;

    @Column(name = "sex", length = 10)
    private String sex;

    @Column(name = "password_ciphertext", length = 1000)
    private String passwordCiphertext;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

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
