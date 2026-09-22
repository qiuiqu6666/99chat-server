package com.chat99.server.user;

import com.chat99.server.user.FriendApplicationHistory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendApplicationRepository extends JpaRepository<FriendApplicationHistory, Long> {

    @Query("""
        SELECT e FROM FriendApplicationHistory e
        WHERE e.userId = :userId
          AND e.addTime < :cursor
          AND e.deleted = false
        ORDER BY e.addTime DESC, e.id DESC
        """)
    List<FriendApplicationHistory> findByUserIdBeforeCursor(
        @Param("userId") String userId,
        @Param("cursor") Instant cursor,
        Pageable pageable);

    @Query("""
        SELECT e FROM FriendApplicationHistory e
        WHERE e.userId = :userId AND e.deleted = false
        ORDER BY e.addTime DESC, e.id DESC
        """)
    List<FriendApplicationHistory> findByUserId(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT e FROM FriendApplicationHistory e WHERE e.userId = :userId AND e.id = :id AND e.deleted = false")
    Optional<FriendApplicationHistory> findByIdAndUserId(
        @Param("id") Long id,
        @Param("userId") String userId);

    @Query("SELECT e FROM FriendApplicationHistory e "
        + "WHERE e.userId = :userId AND e.peerUserId = :peerUserId AND e.status = :status AND e.deleted = false")
    Optional<FriendApplicationHistory> findByUserIdAndPeerUserIdAndStatus(
        @Param("userId") String userId,
        @Param("peerUserId") String peerUserId,
        @Param("status") com.chat99.server.user.FriendApplicationHistory.Status status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value="UPDATE FriendApplicationHistory e "
        + "SET e.deleted = true, e.deletedAt = CURRENT_TIMESTAMP, e.itemVersion = e.itemVersion + 1 "
        + "WHERE e.userId = :userId AND e.id = :id AND e.deleted = false")
    public int softDelete(@Param("userId") String userId, @Param("id") long id);
}
