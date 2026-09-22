package com.chat99.server.chatattachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_attachment")
@Getter
@Setter
@NoArgsConstructor
public class ChatAttachment {

    @Id
    @Column(name = "attachment_id", nullable = false, length = 48)
    private String attachmentId;

    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    @Column(name = "parent_attachment_id", length = 48)
    private String parentAttachmentId;

    @Column(name = "storage_provider", nullable = false, length = 32)
    private String storageProvider = "aliyun-oss";

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ChatAttachmentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "native_message_kind", nullable = false, length = 16)
    private ChatNativeMessageKind nativeMessageKind;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "declared_size_bytes", nullable = false)
    private long declaredSizeBytes;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "declared_checksum_algorithm", length = 32)
    private String declaredChecksumAlgorithm;

    @Column(name = "declared_checksum", length = 128)
    private String declaredChecksum;

    @Column(name = "checksum_algorithm", length = 32)
    private String checksumAlgorithm;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "checksum_status", nullable = false, length = 16)
    private ChatChecksumStatus checksumStatus = ChatChecksumStatus.unverified;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChatAttachmentStatus status = ChatAttachmentStatus.uploading;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "thumbnail_attachment_id", length = 48)
    private String thumbnailAttachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_provenance", length = 16)
    private ChatMetadataProvenance metadataProvenance;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_probe_source", length = 16)
    private ChatMetadataProvenance mediaProbeSource;

    @Column(name = "media_probed_at")
    private Instant mediaProbedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "purge_after")
    private Instant purgeAfter;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
