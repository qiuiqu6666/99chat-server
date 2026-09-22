package com.chat99.server.sticker;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StickerPackItemRepository extends JpaRepository<StickerPackItem, Long> {

    List<StickerPackItem> findByPackIdOrderBySortOrderAsc(String packId);

    Optional<StickerPackItem> findByPackIdAndStickerId(String packId, String stickerId);

    void deleteByPackIdAndStickerId(String packId, String stickerId);

    @Query("""
        SELECT i FROM StickerPackItem i
        JOIN Sticker s ON s.stickerId = i.stickerId
        WHERE i.packId = :packId AND s.ownerUserId = :ownerUserId AND s.status = 'active'
        ORDER BY i.sortOrder ASC
        """)
    List<StickerPackItem> findUserUploadItems(@Param("packId") String packId,
                                              @Param("ownerUserId") String ownerUserId);

    @Query("SELECT COALESCE(MAX(i.sortOrder), -1) FROM StickerPackItem i WHERE i.packId = :packId")
    int maxSortOrder(@Param("packId") String packId);
}
