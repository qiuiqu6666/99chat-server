package com.chat99.server.sticker;

import com.chat99.server.sticker.StickerEnums.MediaType;
import com.chat99.server.sticker.StickerEnums.StickerStatus;
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
@Table(name = "sticker")
@Getter
@Setter
@NoArgsConstructor
public class Sticker {

    @Id
    @Column(name = "sticker_id", length = 64)
    private String stickerId;

    @Column(name = "owner_user_id", length = 32)
    private String ownerUserId;

    @Column(name = "thumb_url", nullable = false, length = 1024)
    private String thumbUrl;

    @Column(name = "origin_url", nullable = false, length = 1024)
    private String originUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    private MediaType mediaType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StickerStatus status = StickerStatus.active;

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
