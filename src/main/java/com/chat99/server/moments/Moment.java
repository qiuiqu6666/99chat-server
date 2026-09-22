package com.chat99.server.moments;

import com.chat99.server.moments.MomentEnums.Visibility;
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
@Table(name = "moment", uniqueConstraints = {
    @UniqueConstraint(name = "uk_moment_id", columnNames = "moment_id"),
    @UniqueConstraint(name = "uk_moment_author_idempotency", columnNames = {"author_user_id", "idempotency_key"})
}, indexes = {
    @Index(name = "idx_moment_feed", columnList = "author_user_id, status, created_at"),
    @Index(name = "idx_moment_created", columnList = "status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Moment {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DELETED = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "moment_id", nullable = false, length = 64)
    private String momentId;

    @Column(name = "author_user_id", nullable = false, length = 64)
    private String authorUserId;

    @Column(name = "text", length = 2000)
    private String text;

    @Column(name = "location", length = 100)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private Visibility visibility = Visibility.FRIENDS;

    @Column(name = "status", nullable = false)
    private int status = STATUS_ACTIVE;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

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
