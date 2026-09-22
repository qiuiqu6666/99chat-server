package com.chat99.server.sticker;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserStickerPackItemRepository extends JpaRepository<UserStickerPackItem, UserStickerPackItemId> {

    List<UserStickerPackItem> findByUserIdAndPackIdOrderBySortOrderAsc(String userId, String packId);

    Optional<UserStickerPackItem> findByUserIdAndPackIdAndStickerId(String userId, String packId, String stickerId);

    void deleteByUserIdAndPackIdAndStickerId(String userId, String packId, String stickerId);

    @Query("""
        SELECT COALESCE(MAX(i.sortOrder), -1)
        FROM UserStickerPackItem i
        WHERE i.userId = :userId AND i.packId = :packId
        """)
    int maxSortOrder(@Param("userId") String userId, @Param("packId") String packId);
}
