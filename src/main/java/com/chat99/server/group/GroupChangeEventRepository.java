package com.chat99.server.group;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupChangeEventRepository extends JpaRepository<GroupChangeEvent, String> {

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.groupId = :groupId
          AND e.occurredAt > :since
          AND (:actions IS NULL OR e.action IN :actions)
        ORDER BY e.occurredAt ASC, e.changeEventId ASC
        """)
    List<GroupChangeEvent> findByGroupSinceActions(
        @Param("groupId") String groupId,
        @Param("since") long since,
        @Param("actions") java.util.Collection<String> actions,
        Pageable pageable);

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.groupId IS NOT NULL AND e.groupId <> ''
          AND EXISTS (SELECT 1 FROM GroupMember g
                     WHERE g.groupId = e.groupId AND g.userId = :userId)
          AND e.occurredAt > :since
          AND (:actions IS NULL OR e.action IN :actions)
        ORDER BY e.occurredAt ASC, e.changeEventId ASC
        """)
    List<GroupChangeEvent> findForUserSinceActions(
        @Param("userId") String userId,
        @Param("since") long since,
        @Param("actions") java.util.Collection<String> actions,
        Pageable pageable);

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.groupId = :groupId
          AND e.action = :action
          AND e.occurredAt > :since
        ORDER BY e.occurredAt DESC, e.changeEventId DESC
        """)
    List<GroupChangeEvent> findRecentByGroupAndAction(
        @Param("groupId") String groupId,
        @Param("action") String action,
        @Param("since") long since,
        Pageable pageable);

    @Query("""
        SELECT e FROM GroupChangeEvent e WHERE e.changeEventId = :changeEventId
        """)
    List<GroupChangeEvent> findByChangeEventId(
        @Param("changeEventId") String changeEventId,
        @Param("since") long since,
        @Param("actions") java.util.Collection<String> actions,
        Pageable pageable);

    @Query("SELECT MIN(e.groupSeq) FROM GroupChangeEvent e WHERE e.groupSeq IS NOT NULL")
    Long findMinGroupSeq();

    @Query("SELECT MAX(e.groupSeq) FROM GroupChangeEvent e WHERE e.groupSeq IS NOT NULL")
    Long findMaxGroupSeq();

    @Query("""
        SELECT DISTINCT e.groupId FROM GroupChangeEvent e WHERE e.action = :action
        """)
    java.util.List<String> findGroupIdsWithActionButProfileNotDismissed(
        @Param("action") String action);

    /* ===================== v2 sync 接口（用 stream 前缀避免 Spring Data derivation 解析） ===================== */

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.revision > :sinceRevision
        ORDER BY e.revision ASC, e.changeEventId ASC
        """)
    List<GroupChangeEvent> findForUserSinceRevision(
        @Param("sinceRevision") long sinceRevision,
        Pageable pageable);

    @Query("SELECT MAX(e.revision) FROM GroupChangeEvent e")
    Long findMaxRevision();

    @Query("SELECT MIN(e.revision) FROM GroupChangeEvent e")
    Long findMinRevision();

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.revision = :revision
        ORDER BY e.changeEventId ASC
        """)
    List<GroupChangeEvent> findForUserAtRevision(
        @Param("revision") long revision,
        Pageable pageable);

    @Query("SELECT e FROM GroupChangeEvent e WHERE e.groupId = :groupId AND e.changeEventId = :changeEventId")
    java.util.Optional<GroupChangeEvent> findByGroupIdAndChangeEventId(
        @Param("groupId") String groupId,
        @Param("changeEventId") String changeEventId);

    /* ===================== 老方法（deprecate）：by seq，保留给 Phase 1 旧调用 ===================== */

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.groupId = :groupId AND e.occurredAt > :since
        ORDER BY e.occurredAt ASC, e.changeEventId ASC
        """)
    List<GroupChangeEvent> streamGroupAfter(
        @Param("groupId") String groupId,
        @Param("since") long since,
        Pageable pageable);

    @Query("""
        SELECT e FROM GroupChangeEvent e
        WHERE e.groupId IS NOT NULL AND e.groupId <> ''
          AND EXISTS (SELECT 1 FROM GroupMember g
                     WHERE g.groupId = e.groupId AND g.userId = :userId)
          AND e.occurredAt > :since
        ORDER BY e.occurredAt ASC, e.changeEventId ASC
        """)
    List<GroupChangeEvent> findForUserSinceSeq(
        @Param("userId") String userId,
        @Param("since") long since,
        Pageable pageable);
}
