package com.chat99.server.sticker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sticker_pack_item", uniqueConstraints = {
    @UniqueConstraint(name = "uk_pack_sticker", columnNames = {"pack_id", "sticker_id"})
}, indexes = {
    @Index(name = "idx_pack_sort", columnList = "pack_id, sort_order")
})
@Getter
@Setter
@NoArgsConstructor
public class StickerPackItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_id", nullable = false, length = 64)
    private String packId;

    @Column(name = "sticker_id", nullable = false, length = 64)
    private String stickerId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
