package com.chat99.server.group;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupNoticeInboxChangeRepository extends JpaRepository<GroupNoticeInboxChange, Long> {

    /** 增量（v2）：按 revision 区间拉事件，用于 opaqueCursor。 */
    @Query("""
        SELECT e FROM GroupNoticeInboxChange e
        WHERE e.userId = :userId
          AND e.revision > :sinceRevision
        ORDER BY e.revision ASC, e.seq ASC
        """)
    List<GroupNoticeInboxChange> findForUserSinceRevision(
        @Param("userId") String userId,
        @Param("sinceRevision") long sinceRevision,
        Pageable pageable);

    @Query("SELECT MAX(e.revision) FROM GroupNoticeInboxChange e WHERE e.userId = :userId")
    Long findMaxRevisionForUser(@Param("userId") String userId);

    @Query("SELECT MIN(e.revision) FROM GroupNoticeInboxChange e WHERE e.userId = :userId")
    Long findMinRevisionForUser(@Param("userId") String userId);

    /** 快照：拉当前 revision 所有事件（数据稳定副本）。 */
    @Query("""
        SELECT e FROM GroupNoticeInboxChange e
        WHERE e.userId = :userId
          AND e.revision = :revision
        ORDER BY e.seq ASC
        """)
    List<GroupNoticeInboxChange> findForUserAtRevision(
        @Param("userId") String userId,
        @Param("revision") long revision,
        Pageable pageable);

    /** 旧协议（deprecate）：按 seq 拉增量。 */
    @Query("""
        SELECT e FROM GroupNoticeInboxChange e
        WHERE e.userId = :userId
          AND e.seq > :sinceSeq
        ORDER BY e.seq ASC
        """)
    List<GroupNoticeInboxChange> findForUserSinceSeq(
        @Param("userId") String userId,
        @Param("sinceSeq") long sinceSeq,
        Pageable pageable);

    @Query("SELECT MIN(e.seq) FROM GroupNoticeInboxChange e")
    Long findMinSeq();

    @Query("SELECT MAX(e.seq) FROM GroupNoticeInboxChange e")
    Long findMaxSeq();

    /** item_version 用：按 userId 全量扫描（用于 nextItemVersion）。 */
    @Query("SELECT e FROM GroupNoticeInboxChange e WHERE e.userId = :userId")
    List<GroupNoticeInboxChange> findAllByUserIdOrderBySeqAsc(@Param("userId") String userId);

    java.util.Optional<GroupNoticeInboxChange> findByEventId(String eventId);
}
