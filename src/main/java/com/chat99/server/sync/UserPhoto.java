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
@Table(name = "user_photo", uniqueConstraints = {
    @UniqueConstraint(name = "uk_photo_uuid", columnNames = "photo_uuid"),
    @UniqueConstraint(name = "uk_user_hash", columnNames = {"user_id", "content_hash"}),
    @UniqueConstraint(name = "uk_user_local_asset", columnNames = {"user_id", "local_asset_id"})
}, indexes = {
    @Index(name = "idx_user_taken", columnList = "user_id, taken_at"),
    @Index(name = "idx_user_session", columnList = "sync_session_id"),
    @Index(name = "idx_photo_status_created", columnList = "status, created_at, id"),
    @Index(name = "idx_photo_user_status_created",
        columnList = "user_id, status, created_at, id")
})
@Getter
@Setter
@NoArgsConstructor
public class UserPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "photo_uuid", nullable = false, length = 36)
    private String photoUuid;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "local_asset_id", nullable = false, length = 128)
    private String localAssetId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", length = 64)
    private String mimeType;

    @Column(name = "media_type", length = 16)
    private String mediaType = SyncEnums.MediaType.IMAGE.name();

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "oss_origin_key", nullable = false, length = 512)
    private String ossOriginKey;

    @Column(name = "oss_thumb_key", length = 512)
    private String ossThumbKey;

    @Column(name = "oss_preview_key", length = 512)
    private String ossPreviewKey;

    @Column(name = "origin_url", length = 1024)
    private String originUrl;

    @Column(name = "thumb_url", length = 1024)
    private String thumbUrl;

    @Column(name = "preview_url", length = 1024)
    private String previewUrl;

    @Column(name = "sync_session_id", length = 36)
    private String syncSessionId;

    @Column(name = "status", nullable = false)
    private int status = 1;

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
