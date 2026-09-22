package com.chat99.server.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUserId(String userId);

    List<User> findByUserIdIn(Collection<String> userIds);

    Optional<User> findByPhone(String phone);

    Optional<User> findByNickname(String nickname);

    List<User> findByPhoneIn(Collection<String> phones);

    boolean existsByUserId(String userId);

    boolean existsByPhone(String phone);

    boolean existsByNickname(String nickname);

    boolean existsByNicknameAndUserIdNot(String nickname, String userId);

    @Query("SELECT u.userId AS userId, u.lastActiveAt AS lastActiveAt, "
        + "u.lastActiveVisibility AS lastActiveVisibility "
        + "FROM User u WHERE u.userId IN :ids")
    List<OnlinePresenceView> findOnlinePresenceByUserIds(@Param("ids") Collection<String> ids);

    long countByCreatedAtGreaterThanEqual(Instant createdAt);

    @Query("SELECT COUNT(u) FROM User u WHERE u.lastActiveAt >= :since")
    long countByLastActiveAtGreaterThanEqual(@Param("since") Instant since);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActiveAt = :ts WHERE u.userId = :userId")
    int touchLastActive(@Param("userId") String userId, @Param("ts") Instant ts);

    Page<User> findByStatusAndIdGreaterThanOrderByIdAsc(int status, long id, Pageable pageable);

    List<User> findByCreatedAtGreaterThanEqual(Instant createdAt);

    interface LastSeenView {
        String getUserId();
        Instant getLastActiveAt();
    }

    interface OnlinePresenceView {
        String getUserId();
        Instant getLastActiveAt();
        LastActiveVisibility getLastActiveVisibility();
    }
}
