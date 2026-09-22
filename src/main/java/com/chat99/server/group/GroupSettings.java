package com.chat99.server.group;

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
@Table(name = "group_settings")
@Getter
@Setter
@NoArgsConstructor
public class GroupSettings {

    @Id
    @Column(name = "group_id", length = 128)
    private String groupId;

    @Column(name = "privacy_protection_enabled", nullable = false)
    private boolean privacyProtectionEnabled;

    /** 群游戏开关（按群白名单，默认关闭） */
    @Column(name = "game_enabled", nullable = false)
    private boolean gameEnabled = false;

    /** IM AppDefinedData /gameid；空串 = 未绑定。 */
    @Column(name = "gameid", nullable = false, length = 128)
    private String gameid = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_join_option", nullable = false, length = 32)
    private GroupJoinOption applyJoinOption = GroupJoinOption.need_permission;

    @Enumerated(EnumType.STRING)
    @Column(name = "invite_join_option", nullable = false, length = 32)
    private GroupJoinOption inviteJoinOption = GroupJoinOption.need_permission;

    @Column(name = "allow_join_by_qr_code", nullable = false)
    private boolean allowJoinByQrCode = true;

    @Column(name = "allow_join_by_alias", nullable = false)
    private boolean allowJoinByAlias = true;

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
