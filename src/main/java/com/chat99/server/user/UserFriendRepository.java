package com.chat99.server.user;

import com.chat99.server.user.UserFriend;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFriendRepository extends JpaRepository<UserFriend, Long> {

    @Query("SELECT e FROM UserFriend e WHERE e.userId = :userId AND e.friendUserId = :friendUserId AND e.deleted = false")
    Optional<UserFriend> findByUserIdAndFriendUserId(@Param("userId") String var1, @Param("friendUserId") String var2);

    /** 含 tombstone 行的对账查询：IM 同步时用于复活软删边（deleted=1 → 0）。 */
    @Query("SELECT e FROM UserFriend e WHERE e.userId = :userId AND e.friendUserId = :friendUserId")
    Optional<UserFriend> findIncludingDeletedByUserIdAndFriendUserId(@Param("userId") String var1, @Param("friendUserId") String var2);

    /** 默认过滤 deleted=false 的活跃列表（向后兼容 API） */
    @Query("SELECT e FROM UserFriend e WHERE e.userId = :userId AND e.status = :status AND e.deleted = false")
    List<UserFriend> findByUserIdAndStatus(@Param("userId") String var1, @Param("status") int var2);

    @Query("SELECT e FROM UserFriend e WHERE e.userId = :userId AND e.status = :status AND e.id > :id AND e.deleted = false")
    List<UserFriend> findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
        String userId, int status, long id, Pageable pageable);

    @Query("SELECT f FROM UserFriend f WHERE f.userId = :viewerUserId AND f.status = :status AND f.friendUserId IN :peerIds AND f.deleted = false")
    List<UserFriend> findByUserIdAndFriendUserIdInAndStatus(@Param("viewerUserId") String var1, @Param("peerIds") Collection<String> var2, @Param("status") int var3);

    @Query("SELECT u.userId FROM UserFriend u WHERE u.friendUserId = :friendUserId AND u.status = :status AND u.deleted = false")
    List<String> findOwnerUserIdsByFriendUserIdAndStatus(@Param("friendUserId") String var1, @Param("status") int var2);

    @Query("SELECT COUNT(f) FROM UserFriend f WHERE f.userId = :userId AND f.status = :status AND f.deleted = false")
    long countByUserIdAndStatus(@Param("userId") String var1, @Param("status") int var2);

    @Query("SELECT f.userId, COUNT(f) FROM UserFriend f WHERE f.userId IN :userIds AND f.status = :status AND f.deleted = false GROUP BY f.userId")
    List<Object[]> countByUserIdsAndStatus(@Param("userIds") Collection<String> userIds, @Param("status") int status);

    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM UserFriend e WHERE e.userId = :userId AND e.friendUserId = :friendUserId AND e.status = :status AND e.deleted = false")
    boolean existsByUserIdAndFriendUserIdAndStatus(@Param("userId") String var1, @Param("friendUserId") String var2, @Param("status") int var3);

    @Query("""
        SELECT f FROM UserFriend f
        WHERE f.deleted = false AND f.status = 1
          AND ((f.userId = :a AND f.friendUserId = :b) OR (f.userId = :b AND f.friendUserId = :a))
        """)
    List<UserFriend> findActiveEdgesBetween(@Param("a") String a, @Param("b") String b);

    @Query("""
        SELECT CASE WHEN COUNT(r) = 2 THEN true ELSE false END
        FROM UserFriend r
        WHERE r.deleted = false AND r.status = 1
          AND ((r.userId = :a AND r.friendUserId = :b) OR (r.userId = :b AND r.friendUserId = :a))
        """)
    boolean isMutualActive(@Param("a") String var1, @Param("b") String var2);

    @Query("""
        SELECT f1.friendUserId FROM UserFriend f1
        WHERE f1.userId = :userId AND f1.status = 1 AND f1.deleted = false
          AND f1.friendUserId IN :peerIds
          AND EXISTS (
              SELECT 1 FROM UserFriend f2
              WHERE f2.userId = f1.friendUserId AND f2.friendUserId = :userId AND f2.status = 1 AND f2.deleted = false
          )
        """)
    List<String> findMutualFriendUserIdsAmong(@Param("userId") String var1, @Param("peerIds") Collection<String> var2);

    @Modifying
    @Query(value="UPDATE UserFriend u SET u.friendAvatarUrl = :avatarUrl, u.friendAvatarPreviewUrl = :avatarPreviewUrl, u.itemVersion = u.itemVersion + 1, u.updatedAt = :now WHERE u.friendUserId = :friendUserId AND u.status = 1 AND u.deleted = false")
    int updateAvatarByFriendUserId(@Param("friendUserId") String var1, @Param("avatarUrl") String var2, @Param("avatarPreviewUrl") String var3, @Param("now") Instant var4);

    @Modifying
    @Query(value="UPDATE UserFriend u SET u.friendNickname = :nickname, u.itemVersion = u.itemVersion + 1, u.updatedAt = :now WHERE u.friendUserId = :friendUserId AND u.status = 1 AND u.deleted = false")
    int updateNicknameByFriendUserId(@Param("friendUserId") String var1, @Param("nickname") String var2, @Param("now") Instant var3);

    /** 物理删（仅 Phase 1 迁移期使用，之后所有删走 softDelete） */
    public void deleteByUserId(String userId);

    public void deleteByFriendUserId(String friendUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value="UPDATE UserFriend u SET u.deleted = true, u.deletedAt = CURRENT_TIMESTAMP, u.itemVersion = u.itemVersion + 1, u.updatedAt = CURRENT_TIMESTAMP "
        + "WHERE u.userId = :userId AND u.friendUserId = :friendUserId AND u.deleted = false")
    int softDelete(@Param("userId") String userId, @Param("friendUserId") String friendUserId);

    /**
     * The legacy user_friend and users tables use different collations in production.
     * BINARY avoids collation coercion while preserving the required valid-peer filter.
     */
    @Query(value = "SELECT COUNT(*) FROM user_friend f WHERE f.user_id = :userId AND f.status = 1 AND f.deleted = 0 "
        + "AND f.friend_user_id <> :userId AND EXISTS (SELECT 1 FROM users u "
        + "WHERE BINARY u.user_id = BINARY f.friend_user_id AND u.status = 1)", nativeQuery = true)
    long countCurrentSnapshotFriends(@Param("userId") String userId);

    @Query(value = "SELECT f.* FROM user_friend f WHERE f.user_id = :userId AND f.status = 1 AND f.deleted = 0 "
        + "AND f.friend_user_id <> :userId AND f.friend_user_id > :afterFriendUserId "
        + "AND EXISTS (SELECT 1 FROM users u WHERE BINARY u.user_id = BINARY f.friend_user_id AND u.status = 1) "
        + "ORDER BY f.friend_user_id ASC", nativeQuery = true)
    List<UserFriend> findCurrentSnapshotFriends(@Param("userId") String userId,
                                                 @Param("afterFriendUserId") String afterFriendUserId,
                                                 Pageable pageable);

    @Query("SELECT COUNT(f) FROM UserFriend f WHERE f.userId = :userId AND f.status = :status AND f.deleted = :deleted")
    long countByUserIdAndStatusAndDeleted(@Param("userId") String userId, @Param("status") int status, @Param("deleted") boolean deleted);
}
