package com.chat99.server.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface GroupJoinApplicationRepository extends JpaRepository<GroupJoinApplication, Long> {

    List<GroupJoinApplication> findByGroupIdAndStatusOrderByCreatedAtDesc(
        String groupId, GroupJoinApplicationStatus status);

    List<GroupJoinApplication> findByGroupIdOrderByCreatedAtDesc(String groupId);

    @Query("""
        SELECT a FROM GroupJoinApplication a
        WHERE a.groupId = :groupId
        AND a.status = :status
        AND NOT EXISTS (
            SELECT 1 FROM GroupJoinApplicationDismiss d
            WHERE d.userId = :userId AND d.applicationId = a.id
        )
        ORDER BY a.createdAt DESC
        """)
    List<GroupJoinApplication> findByGroupIdAndStatusExcludingDismissed(
        @Param("groupId") String groupId,
        @Param("status") GroupJoinApplicationStatus status,
        @Param("userId") String userId);

    @Query("""
        SELECT a FROM GroupJoinApplication a
        WHERE a.groupId = :groupId
        AND NOT EXISTS (
            SELECT 1 FROM GroupJoinApplicationDismiss d
            WHERE d.userId = :userId AND d.applicationId = a.id
        )
        ORDER BY a.createdAt DESC
        """)
    List<GroupJoinApplication> findByGroupIdExcludingDismissed(
        @Param("groupId") String groupId,
        @Param("userId") String userId);

    @Query("""
        SELECT a FROM GroupJoinApplication a
        WHERE a.groupId = :groupId
        AND (:status IS NULL OR a.status = :status)
        ORDER BY a.createdAt DESC
        """)
    List<GroupJoinApplication> findByGroupIdAndOptionalStatus(
        @Param("groupId") String groupId,
        @Param("status") GroupJoinApplicationStatus status);

    @Query("""
        SELECT a FROM GroupJoinApplication a
        WHERE (
            (a.type = com.chat99.server.group.GroupJoinApplicationType.apply AND a.fromUserId = :userId)
            OR (a.type = com.chat99.server.group.GroupJoinApplicationType.invite
                AND (a.toUserId = :userId OR a.fromUserId = :userId))
            OR EXISTS (
                SELECT 1 FROM GroupMember gm
                WHERE gm.groupId = a.groupId AND gm.userId = :userId AND gm.role >= 300
            )
        )
        AND (:status IS NULL OR a.status = :status)
        AND NOT EXISTS (
            SELECT 1 FROM GroupJoinApplicationDismiss d
            WHERE d.userId = :userId AND d.applicationId = a.id
        )
        ORDER BY a.createdAt DESC
        """)
    Page<GroupJoinApplication> findMyApplications(
        @Param("userId") String userId,
        @Param("status") GroupJoinApplicationStatus status,
        Pageable pageable);

    Optional<GroupJoinApplication> findByIdAndGroupId(Long id, String groupId);

    @Query("""
        SELECT DISTINCT a.toUserId FROM GroupJoinApplication a
        WHERE a.groupId = :groupId
        AND a.type = com.chat99.server.group.GroupJoinApplicationType.invite
        AND a.status = com.chat99.server.group.GroupJoinApplicationStatus.pending
        ORDER BY a.toUserId ASC
        """)
    List<String> findPendingInviteeUserIds(@Param("groupId") String groupId);

    boolean existsByGroupIdAndTypeAndFromUserIdAndToUserIdAndStatus(
        String groupId,
        GroupJoinApplicationType type,
        String fromUserId,
        String toUserId,
        GroupJoinApplicationStatus status);

    @Modifying
    @Transactional
    @Query("DELETE FROM GroupJoinApplication a WHERE a.groupId = :groupId AND a.id IN :ids")
    int deleteByGroupIdAndIdIn(@Param("groupId") String groupId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM GroupJoinApplication a
        WHERE a.groupId = :groupId
        AND (:status IS NULL OR a.status = :status)
        """)
    int deleteByGroupIdAndOptionalStatus(
        @Param("groupId") String groupId,
        @Param("status") GroupJoinApplicationStatus status);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM GroupJoinApplication a
        WHERE a.groupId = :groupId
        AND a.status IN :statuses
        """)
    int deleteByGroupIdAndStatusIn(
        @Param("groupId") String groupId,
        @Param("statuses") Collection<GroupJoinApplicationStatus> statuses);

    @Query("""
        SELECT a.id FROM GroupJoinApplication a
        WHERE (
            (a.type = com.chat99.server.group.GroupJoinApplicationType.apply AND a.fromUserId = :userId)
            OR (a.type = com.chat99.server.group.GroupJoinApplicationType.invite
                AND (a.toUserId = :userId OR a.fromUserId = :userId))
            OR EXISTS (
                SELECT 1 FROM GroupMember gm
                WHERE gm.groupId = a.groupId AND gm.userId = :userId AND gm.role >= 300
            )
        )
        AND (:status IS NULL OR a.status = :status)
        AND NOT EXISTS (
            SELECT 1 FROM GroupJoinApplicationDismiss d
            WHERE d.userId = :userId AND d.applicationId = a.id
        )
        """)
    List<Long> findVisibleUndismissedApplicationIds(
        @Param("userId") String userId,
        @Param("status") GroupJoinApplicationStatus status);
}
