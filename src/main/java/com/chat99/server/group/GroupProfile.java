package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_profile")
@Getter
@Setter
@NoArgsConstructor
public class GroupProfile {

    @Id
    @Column(name = "group_id", length = 128)
    private String groupId;

    @Column(name = "group_type", nullable = false, length = 32)
    private String groupType;

    /** 业务频道标记；IM 中仍为 Community，不能依赖 IM 群类型区分。 */
    @Column(name = "is_channel", nullable = false)
    private boolean channel;

    @Column(name = "group_name", nullable = false, length = 512)
    private String groupName = "";

    @Column(name = "display_alias", nullable = false, length = 128)
    private String displayAlias = "";

    /** IM AppDefinedData /gameid；空串 = 未绑定。 */
    @Column(name = "gameid", nullable = false, length = 128)
    private String gameid = "";

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "avatar_preview_url", length = 1024)
    private String avatarPreviewUrl;

    /** 头像变更版本；仅改头像时递增。 */
    @Column(name = "avatar_version", nullable = false)
    private int avatarVersion;

    @Column(name = "notice", columnDefinition = "TEXT")
    private String notice;

    @Column(name = "notice_updated_at")
    private Instant noticeUpdatedAt;

    /** 最近一次修改群公告的用户 userId。 */
    @Column(name = "notice_updated_by", length = 10)
    private String noticeUpdatedBy;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    @Column(name = "owner_user_id", length = 32)
    private String ownerUserId;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed;

    /** 域全局单调 revision（snapshotRevision 一致性保证），每次写群展示字段 +1。 */
    @Column(name = "revision", nullable = false)
    private long revision;

    /** IM ShutUpAllMember；true = On。 */
    @Column(name = "shut_up_all", nullable = false)
    private boolean shutUpAll;

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
