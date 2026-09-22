package com.chat99.server.user;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendContactChangeRepository extends JpaRepository<FriendContactChange, Long> {

    /**
     * 增量：按 seq 升序（旧协议，deprecate）。
     */
    @Query("""
        SELECT e FROM FriendContactChange e
        WHERE e.accountId = :accountId
          AND e.seq > :sinceSeq
        ORDER BY e.seq ASC
        """)
    List<FriendContactChange> findForAccountSinceSeq(
        @Param("accountId") String accountId,
        @Param("sinceSeq") long sinceSeq,
        Pageable pageable);

    @Query("SELECT MIN(e.seq) FROM FriendContactChange e WHERE e.accountId = :accountId")
    Long findMinSeqForAccount(@Param("accountId") String accountId);

    @Query("SELECT MAX(e.seq) FROM FriendContactChange e WHERE e.accountId = :accountId")
    Long findMaxSeqForAccount(@Param("accountId") String accountId);

    /**
     * 增量（v2 协议）：按 revision 区间拉事件，用于 opaqueCursor。
     * sinceRevision 不含（strict greater than）。
     */
    @Query("""
        SELECT e FROM FriendContactChange e
        WHERE e.accountId = :accountId
          AND e.revision > :sinceRevision
        ORDER BY e.revision ASC, e.seq ASC
        """)
    List<FriendContactChange> findForAccountSinceRevision(
        @Param("accountId") String accountId,
        @Param("sinceRevision") long sinceRevision,
        Pageable pageable);

    @Query("SELECT MAX(e.revision) FROM FriendContactChange e WHERE e.accountId = :accountId")
    Long findMaxRevisionForAccount(@Param("accountId") String accountId);

    @Query("SELECT MIN(e.revision) FROM FriendContactChange e WHERE e.accountId = :accountId")
    Long findMinRevisionForAccount(@Param("accountId") String accountId);

    @Query("SELECT MAX(e.itemVersion) FROM FriendContactChange e WHERE e.accountId = :accountId AND e.peerUserId = :peerUserId")
    Long findMaxItemVersionForAccountAndPeer(@Param("accountId") String accountId,
                                              @Param("peerUserId") String peerUserId);

    /**
     * 快照：拉当前 revision 所有事件（数据稳定副本）。
     * 客户端用 snapshotRevision 比较，确保分页一致。
     */
    @Query("""
        SELECT e FROM FriendContactChange e
        WHERE e.accountId = :accountId
          AND e.revision = :revision
        ORDER BY e.seq ASC
        """)
    List<FriendContactChange> findForAccountAtRevision(
        @Param("accountId") String accountId,
        @Param("revision") long revision,
        Pageable pageable);

    /**
     * 幂等：按 eventId 查询（客户端增量去重）。
     */
    java.util.Optional<FriendContactChange> findByEventId(String eventId);
}
