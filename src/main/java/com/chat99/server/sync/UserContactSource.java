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
@Table(name = "user_contact_source", uniqueConstraints = {
    @UniqueConstraint(name = "uk_contact_source", columnNames = {"user_id", "device_id", "local_contact_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class UserContactSource {

    public static final String LEGACY_DEVICE = "legacy";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "local_contact_id", nullable = false, length = 128)
    private String localContactId;

    @Column(name = "contact_item_id", nullable = false)
    private long contactItemId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

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
