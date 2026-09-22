package com.chat99.server.favorite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "user_message_favorite", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_source_msg", columnNames = {"user_id", "source_msg_id"})
}, indexes = {
    @Index(name = "idx_fav_user_time", columnList = "user_id, favorited_at")
})
@Getter
@Setter
@NoArgsConstructor
public class UserMessageFavorite {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private FavoriteType type;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Column(name = "thumb_url", length = 512)
    private String thumbUrl;

    @Column(name = "media_url", length = 512)
    private String mediaUrl;

    @Column(name = "thumb_object_key", length = 256)
    private String thumbObjectKey;

    @Column(name = "media_object_key", length = 256)
    private String mediaObjectKey;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "source_msg_id", length = 128)
    private String sourceMsgId;

    @Column(name = "source_conv_id", length = 128)
    private String sourceConvId;

    @Column(name = "source_sender_name", length = 64)
    private String sourceSenderName;

    @Column(name = "source_conv_label", length = 128)
    private String sourceConvLabel;

    @Column(name = "is_manual", nullable = false)
    private boolean manual;

    @Column(name = "favorited_at", nullable = false)
    private Instant favoritedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (favoritedAt == null) {
            favoritedAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
