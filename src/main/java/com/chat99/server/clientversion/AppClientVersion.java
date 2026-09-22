package com.chat99.server.clientversion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_client_version",
    uniqueConstraints = @UniqueConstraint(name = "uk_client_version_platform_version",
        columnNames = {"platform", "version"}))
@Getter
@Setter
@NoArgsConstructor
public class AppClientVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private ClientVersionPlatform platform;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "version_code")
    private Integer versionCode;

    @Column(name = "min_version", length = 32)
    private String minVersion;

    @Column(name = "min_version_code")
    private Integer minVersionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "update_type", nullable = false, length = 16)
    private ClientVersionUpdateType updateType = ClientVersionUpdateType.OPTIONAL;

    @Column(name = "download_url", length = 500)
    private String downloadUrl;

    @Column(name = "changelog", columnDefinition = "TEXT")
    private String changelog;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "gray_percent", nullable = false)
    private int grayPercent = 100;

    @Column(name = "published_at")
    private Instant publishedAt;

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
        if (publishedAt == null) {
            publishedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
