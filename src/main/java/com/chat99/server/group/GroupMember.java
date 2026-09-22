package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_member")
@IdClass(GroupMemberId.class)
@Getter
@Setter
@NoArgsConstructor
public class GroupMember {

    @Id
    @Column(name = "group_id", length = 128)
    private String groupId;

    @Id
    @Column(name = "user_id", length = 32)
    private String userId;

    @Column(name = "role", nullable = false)
    private int role = GroupRoleCodec.MEMBER;

    @Column(name = "name_card", length = 512)
    private String nameCard;

    /** IM MutedUntil（Unix 秒）；null/0 表示未禁言。 */
    @Column(name = "muted_until")
    private Long mutedUntil;

    /** 邀请人业务 user_id；自行入群 / 历史为 null。 */
    @Column(name = "invited_by", length = 32)
    private String invitedBy;

    /** {@link GroupMemberJoinChannel#INVITE} / {@link GroupMemberJoinChannel#GROUP_ID}；历史为 null。 */
    @Column(name = "join_channel", length = 32)
    private String joinChannel;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Tombstone 软删标记：保留物理行，deleted=1 的行不参与业务读。 */
    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    /** 软删时间。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 单条实体版本号（snapshot 去重用），每次软删/复活/复活性写 +1。 */
    @Column(name = "item_version", nullable = false)
    private long itemVersion = 0L;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
