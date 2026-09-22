package com.chat99.server.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StarredFriendRepository extends JpaRepository<StarredFriend, Long> {

    List<StarredFriend> findByUserIdOrderByStarredAtDesc(String userId);

    Optional<StarredFriend> findByUserIdAndFriendUserId(String userId, String friendUserId);

    boolean existsByUserIdAndFriendUserId(String userId, String friendUserId);

    void deleteByUserIdAndFriendUserId(String userId, String friendUserId);
}
