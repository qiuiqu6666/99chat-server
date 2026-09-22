package com.chat99.server.user;

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
@Table(
    name = "friend_application_history",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_user_peer_status",
            columnNames = {"user_id", "peer_user_id", "status"
            })
    },
    indexes = {
        @Index(name = "idx_user_addtime", columnList = "user_id, add_time DESC")
    })
@Getter
@Setter
@NoArgsConstructor
public class FriendApplicationHistory {

    public enum AddSource {
        qr_code,
        search,
        phone,
        nearby,
        card,
        group
    }

    public enum Status {
        accepted,
        rejected
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "peer_user_id", nullable = false, length = 64)
    private String peerUserId;

    @Column(name = "peer_nickname", nullable = false, length = 100)
    private String peerNickname;

    @Column(name = "peer_face_url", length = 500)
    private String peerFaceUrl;

    @Column(name = "add_wording", length = 200)
    private String addWording;

    @Enumerated(EnumType.STRING)
    @Column(name = "add_source", nullable = false, length = 20)
    private AddSource addSource;

    @Column(name = "add_time", nullable = false)
    private Instant addTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Tombstone：1 = 用户手动删除的历史记录（软删）。 */
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 单条实体版本号，软删 / 复活时 +1。 */
    @Column(name = "item_version", nullable = false)
    private long itemVersion = 0L;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
