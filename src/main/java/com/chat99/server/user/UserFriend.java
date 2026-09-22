package com.chat99.server.user;

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
@Table(name = "user_friend", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_friend", columnNames = {"user_id", "friend_user_id"})
}, indexes = {
    @Index(name = "idx_user_friend_status", columnList = "user_id, status"),
    @Index(name = "idx_user_friend_peer", columnList = "friend_user_id, status"),
    @Index(name = "idx_user_friend_item_ver", columnList = "user_id, item_version")
})
@Getter
@Setter
@NoArgsConstructor
public class UserFriend {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_REMOVED = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "friend_user_id", nullable = false, length = 64)
    private String friendUserId;

    @Column(name = "friend_nickname", length = 100)
    private String friendNickname;

    @Column(name = "friend_avatar_url", length = 500)
    private String friendAvatarUrl;

    @Column(name = "friend_avatar_preview_url", length = 500)
    private String friendAvatarPreviewUrl;

    @Column(name = "remark", length = 100)
    private String remark;

    @Column(name = "added_at")
    private Instant addedAt;

    @Column(name = "im_add_time")
    private Instant imAddTime;

    @Column(name = "status", nullable = false)
    private int status = STATUS_ACTIVE;

    /** 单条实体的修订号，每次写 +1。snapshot 去重 / 防止旧数据覆盖新数据。 */
    @Column(name = "item_version", nullable = false)
    private long itemVersion;

    /** Tombstone：1 = 已删除（软删）。客户端应删除本地缓存。 */
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (syncedAt == null) {
            syncedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
