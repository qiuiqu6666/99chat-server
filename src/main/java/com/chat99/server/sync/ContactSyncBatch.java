package com.chat99.server.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "contact_sync_batch", uniqueConstraints = {
    @UniqueConstraint(name = "uk_contact_batch", columnNames = {"session_uuid", "batch_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ContactSyncBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_uuid", nullable = false, length = 36)
    private String sessionUuid;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;

    @Column(name = "result_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String resultJson;

    @Column(name = "uploaded", nullable = false)
    private int uploaded;

    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
