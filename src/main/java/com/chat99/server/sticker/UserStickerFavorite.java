/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.sticker;

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
import lombok.Generated;

@Entity
@Table(name="user_sticker_favorite", uniqueConstraints={@UniqueConstraint(name="uk_user_sticker_fav", columnNames={"user_id", "sticker_id"})}, indexes={@Index(name="idx_sticker_fav_user_time", columnList="user_id, favorited_at")})
public class UserStickerFavorite {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(name="user_id", nullable=false, length=32)
    private String userId;
    @Column(name="sticker_id", nullable=false, length=64)
    private String stickerId;
    @Column(name="favorited_at", nullable=false, updatable=false)
    private Instant favoritedAt;

    @PrePersist
    void onCreate() {
        if (this.favoritedAt == null) {
            this.favoritedAt = Instant.now();
        }
    }

    @Generated
    public Long getId() {
        return this.id;
    }

    @Generated
    public String getUserId() {
        return this.userId;
    }

    @Generated
    public String getStickerId() {
        return this.stickerId;
    }

    @Generated
    public Instant getFavoritedAt() {
        return this.favoritedAt;
    }

    @Generated
    public void setId(Long id) {
        this.id = id;
    }

    @Generated
    public void setUserId(String userId) {
        this.userId = userId;
    }

    @Generated
    public void setStickerId(String stickerId) {
        this.stickerId = stickerId;
    }

    @Generated
    public void setFavoritedAt(Instant favoritedAt) {
        this.favoritedAt = favoritedAt;
    }

    @Generated
    public UserStickerFavorite() {
    }
}
