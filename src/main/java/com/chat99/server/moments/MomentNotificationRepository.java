package com.chat99.server.moments;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MomentNotificationRepository extends JpaRepository<MomentNotification, Long> {

    long countByRecipientUserIdAndReadFalse(String recipientUserId);

    @Query("""
        SELECT n FROM MomentNotification n
        WHERE n.recipientUserId = :recipientUserId
          AND (:cursorCreatedAt IS NULL
            OR n.createdAt < :cursorCreatedAt
            OR (n.createdAt = :cursorCreatedAt AND n.notificationId < :cursorNotificationId))
        ORDER BY n.createdAt DESC, n.notificationId DESC
        """)
    List<MomentNotification> findPage(@Param("recipientUserId") String recipientUserId,
                                      @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                      @Param("cursorNotificationId") String cursorNotificationId,
                                      Pageable pageable);

    @Modifying
    @Query("UPDATE MomentNotification n SET n.read = true WHERE n.recipientUserId = :recipientUserId AND n.read = false")
    int markAllRead(@Param("recipientUserId") String recipientUserId);

    @Modifying
    @Query("""
        UPDATE MomentNotification n SET n.read = true
        WHERE n.recipientUserId = :recipientUserId
          AND n.notificationId IN :notificationIds
          AND n.read = false
        """)
    int markRead(@Param("recipientUserId") String recipientUserId,
                 @Param("notificationIds") Collection<String> notificationIds);
}
