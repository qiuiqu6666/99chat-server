package com.chat99.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_id", columnNames = "user_id"),
    @UniqueConstraint(name = "uk_phone", columnNames = "phone"),
    @UniqueConstraint(name = "uk_nickname", columnNames = "nickname")
}, indexes = {
    @Index(name = "idx_last_active", columnList = "last_active_at")
})
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "phone_country", length = 4)
    private String phoneCountry;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "nickname", nullable = false, length = 32)
    private String nickname;

    @Column(name = "avatar_url", nullable = false, length = 255)
    private String avatarUrl;

    @Column(name = "avatar_preview_url", length = 255)
    private String avatarPreviewUrl;

    @Column(name = "avatar_version", nullable = false)
    private int avatarVersion;

    @Column(name = "last_nickname_changed_at")
    private Instant lastNicknameChangedAt;

    @Column(name = "allow_via_qr_code", nullable = false)
    private boolean allowViaQrCode = true;

    @Column(name = "allow_via_card", nullable = false)
    private boolean allowViaCard = true;

    @Column(name = "allow_via_group", nullable = false)
    private boolean allowViaGroup = true;

    @Column(name = "allow_via_phone", nullable = false)
    private boolean allowViaPhone = true;

    @Column(name = "allow_via_uid", nullable = false)
    private boolean allowViaUid = true;

    /** true=添加我时需验证；false=无需验证直接成为好友 */
    @Column(name = "friend_add_requires_verify", nullable = false)
    private boolean friendAddRequiresVerify = true;

    /** 最后上线时间可见性：everyone / friends_only / hidden */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_active_visibility", nullable = false, length = 20)
    private LastActiveVisibility lastActiveVisibility = LastActiveVisibility.everyone;

    @Column(name = "bypass_device_check", nullable = false)
    private boolean bypassDeviceCheck = false;

    /** 运营后台设置：密码登录永久跳过新设备短信验证 */
    @Column(name = "skip_device_sms", nullable = false)
    private boolean skipDeviceSms = false;

    /** 游戏功能特权用户；开启后客户端可展示游戏入口 */
    @Column(name = "game_privileged", nullable = false)
    private boolean gamePrivileged = false;

    /** 未打开 App 时是否接收系统消息通知栏 Push */
    @Column(name = "system_message_notification_enabled", nullable = false)
    private boolean systemMessageNotificationEnabled = true;

    /** 是否接收语音/视频通话 Push（iOS VoIP 等） */
    @Column(name = "call_notification_enabled", nullable = false)
    private boolean callNotificationEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_display_content", nullable = false, length = 16)
    private NotificationDisplayContent notificationDisplayContent = NotificationDisplayContent.show_all;

    @Column(name = "status", nullable = false)
    private int status = 1;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
