package com.chat99.server.sticker;

import com.chat99.server.sticker.StickerEnums.PackSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sticker_pack")
@Getter
@Setter
@NoArgsConstructor
public class StickerPack {

    @Id
    @Column(name = "pack_id", length = 64)
    private String packId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private PackSource source;

    @Column(name = "removable", nullable = false)
    private boolean removable;

    @Column(name = "default_sort_order", nullable = false)
    private int defaultSortOrder;
}
