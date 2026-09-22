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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_native_video_message")
@Getter
@Setter
@NoArgsConstructor
public class ChatNativeVideoMessage {

    @Id
    @Column(name = "operation_id", nullable = false, length = 48)
    private String operationId;

    @Column(name = "sender_user_id", nullable = false, length = 64)
    private String senderUserId;

    @Column(name = "client_operation_id", nullable = false, length = 128)
    private String clientOperationId;

    @Column(name = "attachment_id", nullable = false, length = 48)
    private String attachmentId;

    @Column(name = "reference_id", nullable = false, length = 48)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_type", nullable = false, length = 16)
    private ChatConversationType conversationType;

    @Column(name = "conversation_key", nullable = false, length = 160)
    private String conversationKey;

    @Column(name = "peer_user_id", length = 64)
    private String peerUserId;

    @Column(name = "group_id", length = 128)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChatNativeVideoStatus status = ChatNativeVideoStatus.pending;

    @Column(name = "im_random", nullable = false)
    private int imRandom;

    @Column(name = "msg_key", length = 128)
    private String msgKey;

    @Column(name = "msg_seq")
    private Long msgSeq;

    @Column(name = "fail_code", length = 64)
    private String failCode;

    @Column(name = "media_base_url", length = 255)
    private String mediaBaseUrl;

    @Column(name = "lock_until")
    private Instant lockUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

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
