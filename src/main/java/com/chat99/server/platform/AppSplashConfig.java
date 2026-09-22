package com.chat99.server.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "app_splash_config",
    uniqueConstraints = @UniqueConstraint(name = "uk_app_splash_version", columnNames = "version"))
@Getter
@Setter
@NoArgsConstructor
public class AppSplashConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "image_md5", length = 32)
    private String imageMd5;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "bytes")
    private Integer bytes;

    @Column(name = "fit", nullable = false, length = 32)
    private String fit = "cover";

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "min_app_version", length = 32)
    private String minAppVersion;

    /** comma-separated; blank = all platforms */
    @Column(name = "platforms", length = 128)
    private String platforms;

    /** comma-separated; blank = all channels */
    @Column(name = "channels", length = 256)
    private String channels;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "created_by", length = 64)
    private String createdBy;

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
        if (fit == null || fit.isBlank()) {
            fit = "cover";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
