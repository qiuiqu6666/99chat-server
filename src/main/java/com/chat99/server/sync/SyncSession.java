package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SessionStatus;
import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sync_session", uniqueConstraints = {
    @UniqueConstraint(name = "uk_session_uuid", columnNames = "session_uuid")
}, indexes = {
    @Index(name = "idx_sync_user_type", columnList = "user_id, sync_type, started_at")
})
@Getter
@Setter
@NoArgsConstructor
public class SyncSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_uuid", nullable = false, length = 36)
    private String sessionUuid;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_type", nullable = false, length = 16)
    private SyncType syncType;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_mode", nullable = false, length = 16)
    private SyncMode syncMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SessionStatus status = SessionStatus.RUNNING;

    @Column(name = "uploaded_count", nullable = false)
    private int uploadedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
