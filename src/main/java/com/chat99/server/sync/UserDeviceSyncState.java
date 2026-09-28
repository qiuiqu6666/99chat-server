package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_device_sync_state")
@IdClass(UserDeviceSyncStateId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserDeviceSyncState {

    @Id
    @Column(name = "user_id", length = 10)
    private String userId;

    @Id
    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_type", length = 16)
    private SyncType syncType;

    @Column(name = "last_full_sync_at")
    private Instant lastFullSyncAt;

    @Column(name = "last_incremental_sync_at")
    private Instant lastIncrementalSyncAt;

    @Column(name = "server_revision", nullable = false)
    private long serverRevision;

    @Column(name = "baseline_ready", nullable = false)
    private boolean baselineReady;

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
