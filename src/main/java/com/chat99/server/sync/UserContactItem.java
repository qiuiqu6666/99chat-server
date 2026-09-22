package com.chat99.server.sync;

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
@Table(name = "user_contact_item", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_local", columnNames = {"user_id", "local_contact_id"})
}, indexes = {
    @Index(name = "idx_user_fp", columnList = "user_id, fingerprint"),
    @Index(name = "idx_user_status", columnList = "user_id, status"),
    @Index(name = "idx_contact_status_updated", columnList = "status, updated_at, id"),
    @Index(name = "idx_contact_user_status_updated",
        columnList = "user_id, status, updated_at, id"),
    @Index(name = "idx_contact_platform_updated",
        columnList = "is_platform_user, status, updated_at, id")
})
@Getter
@Setter
@NoArgsConstructor
public class UserContactItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "local_contact_id", nullable = false, length = 128)
    private String localContactId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "phones_json", nullable = false, columnDefinition = "TEXT")
    private String phonesJson;

    @Column(name = "status", nullable = false)
    private int status = 1;

    @Column(name = "is_platform_user", nullable = false)
    private boolean platformUser;

    @Column(name = "matched_user_id", length = 10)
    private String matchedUserId;

    @Column(name = "contact_updated_at")
    private Instant contactUpdatedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (syncedAt == null) {
            syncedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
