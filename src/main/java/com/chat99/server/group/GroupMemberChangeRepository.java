package com.chat99.server.group;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberChangeRepository extends JpaRepository<GroupMemberChange, Long> {

    @Query("""
        SELECT e FROM GroupMemberChange e
        WHERE e.groupId = :groupId
          AND e.revision > :sinceRevision
        ORDER BY e.revision ASC, e.seq ASC
        """)
    List<GroupMemberChange> findForGroupSinceRevision(
        @Param("groupId") String groupId,
        @Param("sinceRevision") long sinceRevision,
        Pageable pageable);

    @Query("SELECT MAX(e.revision) FROM GroupMemberChange e WHERE e.groupId = :groupId")
    Long findMaxRevisionForGroup(@Param("groupId") String groupId);

    @Query("SELECT MIN(e.revision) FROM GroupMemberChange e WHERE e.groupId = :groupId")
    Long findMinRevisionForGroup(@Param("groupId") String groupId);

    @Query("""
        SELECT e FROM GroupMemberChange e
        WHERE e.groupId = :groupId
          AND e.revision = :revision
        ORDER BY e.seq ASC
        """)
    List<GroupMemberChange> findForGroupAtRevision(
        @Param("groupId") String groupId,
        @Param("revision") long revision,
        Pageable pageable);

    @Query("""
        SELECT e FROM GroupMemberChange e
        WHERE e.groupId = :groupId
          AND e.seq > :sinceSeq
        ORDER BY e.seq ASC
        """)
    List<GroupMemberChange> findForGroupSinceSeq(
        @Param("groupId") String groupId,
        @Param("sinceSeq") long sinceSeq,
        Pageable pageable);

    @Query("SELECT MIN(e.seq) FROM GroupMemberChange e WHERE e.groupId = :groupId")
    Long findMinSeqForGroup(@Param("groupId") String groupId);

    @Query("SELECT MAX(e.seq) FROM GroupMemberChange e WHERE e.groupId = :groupId")
    Long findMaxSeqForGroup(@Param("groupId") String groupId);

    java.util.Optional<GroupMemberChange> findByEventId(String eventId);
}
