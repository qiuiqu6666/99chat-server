package com.chat99.server.sticker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_sticker_pack")
@IdClass(UserStickerPackId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserStickerPack {

    @Id
    @Column(name = "user_id", length = 32)
    private String userId;

    @Id
    @Column(name = "pack_id", length = 64)
    private String packId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
