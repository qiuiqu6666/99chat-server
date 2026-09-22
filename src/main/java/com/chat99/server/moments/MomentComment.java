package com.chat99.server.moments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "moment_comment", uniqueConstraints = {
    @UniqueConstraint(name = "uk_moment_comment_id", columnNames = "comment_id"),
    @UniqueConstraint(name = "uk_moment_comment_idempotency", columnNames = {"moment_id", "author_user_id", "idempotency_key"})
}, indexes = {
    @Index(name = "idx_moment_comment_moment", columnList = "moment_id, status, created_at"),
    @Index(name = "idx_moment_comment_author", columnList = "author_user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class MomentComment {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DELETED = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false, length = 64)
    private String commentId;

    @Column(name = "moment_id", nullable = false, length = 64)
    private String momentId;

    @Column(name = "author_user_id", nullable = false, length = 64)
    private String authorUserId;

    @Column(name = "reply_to_comment_id", length = 64)
    private String replyToCommentId;

    @Column(name = "reply_to_user_id", length = 64)
    private String replyToUserId;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    @Column(name = "status", nullable = false)
    private int status = STATUS_ACTIVE;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
