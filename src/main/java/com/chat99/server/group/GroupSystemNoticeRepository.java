package com.chat99.server.group;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupSystemNoticeRepository extends JpaRepository<GroupSystemNotice, String> {

    @Query("""
        SELECT n FROM GroupSystemNotice n
        WHERE (n.operatorUserId = :userId OR n.targetUserId = :userId)
        AND (:since IS NULL OR n.createdAt > :since)
        AND (:unreadAfter IS NULL OR n.createdAt > :unreadAfter)
        AND NOT EXISTS (
            SELECT 1 FROM GroupSystemNoticeDismiss d
            WHERE d.userId = :userId AND d.noticeId = n.noticeId
        )
        ORDER BY n.createdAt DESC
        """)
    Page<GroupSystemNotice> findForUser(
        @Param("userId") String userId,
        @Param("since") Instant since,
        @Param("unreadAfter") Instant unreadAfter,
        Pageable pageable);

    @Query("""
        SELECT COUNT(n) FROM GroupSystemNotice n
        WHERE (n.operatorUserId = :userId OR n.targetUserId = :userId)
        AND n.createdAt > :readAt
        AND NOT EXISTS (
            SELECT 1 FROM GroupSystemNoticeDismiss d
            WHERE d.userId = :userId AND d.noticeId = n.noticeId
        )
        """)
    long countUnreadAfter(@Param("userId") String userId, @Param("readAt") Instant readAt);

    @Query("""
        SELECT COUNT(n) > 0 FROM GroupSystemNotice n
        WHERE n.groupId = :groupId
        AND n.type = :type
        AND n.operatorUserId = :operatorUserId
        AND n.targetUserId = :targetUserId
        AND n.createdAt > :since
        """)
    boolean existsRecent(
        @Param("groupId") String groupId,
        @Param("type") GroupSystemNoticeType type,
        @Param("operatorUserId") String operatorUserId,
        @Param("targetUserId") String targetUserId,
        @Param("since") Instant since);

    @Query("""
        SELECT n.noticeId FROM GroupSystemNotice n
        WHERE (n.operatorUserId = :userId OR n.targetUserId = :userId)
        AND NOT EXISTS (
            SELECT 1 FROM GroupSystemNoticeDismiss d
            WHERE d.userId = :userId AND d.noticeId = n.noticeId
        )
        """)
    List<String> findVisibleUndismissedNoticeIds(@Param("userId") String userId);
}
