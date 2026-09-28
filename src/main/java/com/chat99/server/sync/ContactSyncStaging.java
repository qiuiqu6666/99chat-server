package com.chat99.server.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "contact_sync_staging", uniqueConstraints = {
    @UniqueConstraint(name = "uk_contact_staging", columnNames = {"session_uuid", "local_contact_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ContactSyncStaging {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_uuid", nullable = false, length = 36)
    private String sessionUuid;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "local_contact_id", nullable = false, length = 128)
    private String localContactId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "phones_json", nullable = false, columnDefinition = "TEXT")
    private String phonesJson;

    @Column(name = "contact_updated_at")
    private Instant contactUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
