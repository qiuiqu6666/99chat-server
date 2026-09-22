package com.chat99.server.adminapi;

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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "admin_user_generation_items", uniqueConstraints = {
    @UniqueConstraint(name = "uk_augi_task_index", columnNames = {"task_id", "item_index"})
}, indexes = {
    @Index(name = "idx_augi_task_status", columnList = "task_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class AdminUserGenerationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "item_index", nullable = false)
    private int itemIndex;

    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "user_uid", length = 64)
    private String userUid;

    @Column(name = "trx_address", length = 128)
    private String trxAddress;

    @Column(name = "deposit_address", length = 128)
    private String depositAddress;

    @Column(name = "usdt_contract", length = 128)
    private String usdtContract;

    @Column(name = "min_deposit_usdt", length = 40)
    private String minDepositUsdt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

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
