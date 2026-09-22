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
@Table(name = "chat_attachment_reference")
@Getter
@Setter
@NoArgsConstructor
public class ChatAttachmentReference {

    @Id
    @Column(name = "reference_id", nullable = false, length = 48)
    private String referenceId;

    @Column(name = "attachment_id", nullable = false, length = 48)
    private String attachmentId;

    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

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

    @Column(name = "client_operation_id", nullable = false, length = 128)
    private String clientOperationId;

    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 16)
    private ChatReferenceType referenceType = ChatReferenceType.message;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ChatReferenceState state = ChatReferenceState.reserved;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
