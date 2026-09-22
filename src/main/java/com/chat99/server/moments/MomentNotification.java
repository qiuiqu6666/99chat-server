package com.chat99.server.moments;

import com.chat99.server.moments.MomentEnums.NotificationType;
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
@Table(name = "moment_notification", uniqueConstraints = {
    @UniqueConstraint(name = "uk_moment_notification_id", columnNames = "notification_id")
}, indexes = {
    @Index(name = "idx_moment_notification_recipient", columnList = "recipient_user_id, created_at"),
    @Index(name = "idx_moment_notification_unread", columnList = "recipient_user_id, read_flag")
})
@Getter
@Setter
@NoArgsConstructor
public class MomentNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false, length = 64)
    private String notificationId;

    @Column(name = "recipient_user_id", nullable = false, length = 64)
    private String recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "actor_user_id", nullable = false, length = 64)
    private String actorUserId;

    @Column(name = "moment_id", nullable = false, length = 64)
    private String momentId;

    @Column(name = "comment_id", length = 64)
    private String commentId;

    @Column(name = "reply_to_user_id", length = 64)
    private String replyToUserId;

    @Column(name = "read_flag", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
