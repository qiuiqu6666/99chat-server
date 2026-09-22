package com.chat99.server.sticker;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserStickerPackRepository extends JpaRepository<UserStickerPack, UserStickerPackId> {

    List<UserStickerPack> findByUserIdOrderBySortOrderAsc(String userId);

    boolean existsByUserIdAndPackId(String userId, String packId);

    void deleteByUserIdAndPackId(String userId, String packId);
}
