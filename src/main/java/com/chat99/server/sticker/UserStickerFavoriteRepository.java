package com.chat99.server.sticker;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserStickerFavoriteRepository extends JpaRepository<UserStickerFavorite, Long> {

    List<UserStickerFavorite> findByUserIdOrderByFavoritedAtDesc(String userId);

    Optional<UserStickerFavorite> findByUserIdAndStickerId(String userId, String stickerId);

    void deleteByUserIdAndStickerId(String userId, String stickerId);
}
