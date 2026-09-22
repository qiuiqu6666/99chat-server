package com.chat99.server.push;

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
@Table(name = "user_push_token",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_push_device", columnNames = {"user_id", "device_id"}),
    indexes = {
        @Index(name = "idx_push_user_enabled", columnList = "user_id,enabled"),
        @Index(name = "idx_push_user_apns_enabled", columnList = "user_id,apns_enabled"),
        @Index(name = "idx_push_user_voip_enabled", columnList = "user_id,voip_enabled"),
        @Index(name = "idx_push_token", columnList = "push_token")
    })
@Getter
@Setter
@NoArgsConstructor
public class UserPushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private PushPlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PushProvider provider;

    @Column(name = "push_token", nullable = false, length = 512)
    private String pushToken;

    @Column(name = "voip_push_token", length = 512)
    private String voipPushToken;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 普通通知 Push 状态（APNs；Android/JPUSH 行同样使用该状态）。 */
    @Column(name = "apns_enabled", nullable = false)
    private boolean apnsEnabled = true;

    /** iOS PushKit VoIP Token 状态，与普通通知 Token 独立。 */
    @Column(name = "voip_enabled", nullable = false)
    private boolean voipEnabled = false;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

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
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
