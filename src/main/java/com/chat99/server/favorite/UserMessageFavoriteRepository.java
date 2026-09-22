package com.chat99.server.favorite;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMessageFavoriteRepository extends JpaRepository<UserMessageFavorite, String> {

    Page<UserMessageFavorite> findByUserIdOrderByFavoritedAtDesc(String userId, Pageable pageable);

    Page<UserMessageFavorite> findByUserIdAndTypeOrderByFavoritedAtDesc(
        String userId, FavoriteType type, Pageable pageable);

    Optional<UserMessageFavorite> findByIdAndUserId(String id, String userId);

    Optional<UserMessageFavorite> findByUserIdAndSourceMsgId(String userId, String sourceMsgId);

    List<UserMessageFavorite> findByUserIdAndIdIn(String userId, List<String> ids);
}
