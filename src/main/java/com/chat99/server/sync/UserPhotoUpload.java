package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.UploadStatus;
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
@Table(name = "user_photo_upload", uniqueConstraints = {
    @UniqueConstraint(name = "uk_upload_uuid", columnNames = "upload_uuid")
}, indexes = {
    @Index(name = "idx_upload_user", columnList = "user_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class UserPhotoUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_uuid", nullable = false, length = 36)
    private String uploadUuid;

    @Column(name = "photo_uuid", nullable = false, length = 36)
    private String photoUuid;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "local_asset_id", nullable = false, length = 128)
    private String localAssetId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "oss_origin_key", nullable = false, length = 512)
    private String ossOriginKey;

    @Column(name = "expected_size")
    private Long expectedSize;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "mime_type", length = 64)
    private String mimeType;

    @Column(name = "media_type", length = 16)
    private String mediaType = SyncEnums.MediaType.IMAGE.name();

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "sync_session_id", length = 36)
    private String syncSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UploadStatus status = UploadStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
