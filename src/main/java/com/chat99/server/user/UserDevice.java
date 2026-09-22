package com.chat99.server.user;

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
@Table(name = "user_device",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_device", columnNames = {"user_id", "device_id"}),
    indexes = @Index(name = "idx_user_trusted", columnList = "user_id,is_trusted"))
@Getter
@Setter
@NoArgsConstructor
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "platform", length = 16)
    private String platform;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "is_trusted", nullable = false)
    private boolean trusted = false;

    @Column(name = "trusted_at")
    private Instant trustedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
