package com.chat99.server.moments;

import com.chat99.server.moments.MomentEnums.MediaType;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "moment_media", uniqueConstraints = {
    @UniqueConstraint(name = "uk_moment_media_id", columnNames = "media_id"),
    @UniqueConstraint(name = "uk_moment_media_client", columnNames = {"owner_user_id", "client_media_id"})
}, indexes = {
    @Index(name = "idx_moment_media_owner", columnList = "owner_user_id, moment_id"),
    @Index(name = "idx_moment_media_moment", columnList = "moment_id")
})
@Getter
@Setter
@NoArgsConstructor
public class MomentMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "media_id", nullable = false, length = 64)
    private String mediaId;

    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    @Column(name = "moment_id", length = 64)
    private String momentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MediaType type;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "thumb_url", length = 1000)
    private String thumbUrl;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "thumb_object_key", length = 500)
    private String thumbObjectKey;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "client_media_id", length = 128)
    private String clientMediaId;

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
