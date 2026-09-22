package com.chat99.server.chatattachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_upload_session")
@Getter
@Setter
@NoArgsConstructor
public class ChatUploadSession {

    @Id
    @Column(name = "upload_id", nullable = false, length = 48)
    private String uploadId;

    @Column(name = "attachment_id", nullable = false, length = 48)
    private String attachmentId;

    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    @Column(name = "client_upload_key", nullable = false, length = 128)
    private String clientUploadKey;

    @Column(name = "parent_upload_id", length = 48)
    private String parentUploadId;

    @Column(name = "provider_upload_id", length = 256)
    private String providerUploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_type", nullable = false, length = 16)
    private ChatConversationType conversationType;

    @Column(name = "conversation_key", nullable = false, length = 160)
    private String conversationKey;

    @Column(name = "participant_low", length = 64)
    private String participantLow;

    @Column(name = "participant_high", length = 64)
    private String participantHigh;

    @Column(name = "group_id", length = 128)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ChatAttachmentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "native_message_kind", nullable = false, length = 16)
    private ChatNativeMessageKind nativeMessageKind;

    @Column(name = "declared_size_bytes", nullable = false)
    private long declaredSizeBytes;

    @Column(name = "part_size_bytes", nullable = false)
    private long partSizeBytes;

    @Column(name = "expected_part_count", nullable = false)
    private int expectedPartCount;

    @Column(name = "quota_day", nullable = false)
    private LocalDate quotaDay;

    @Column(name = "reserved_storage_bytes", nullable = false)
    private long reservedStorageBytes;

    @Column(name = "reserved_daily_bytes", nullable = false)
    private long reservedDailyBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChatUploadStatus status = ChatUploadStatus.initiated;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
