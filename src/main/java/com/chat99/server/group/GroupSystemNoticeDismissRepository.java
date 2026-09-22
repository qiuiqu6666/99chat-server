package com.chat99.server.group;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupSystemNoticeDismissRepository
    extends JpaRepository<GroupSystemNoticeDismiss, GroupSystemNoticeDismissId>,
        GroupSystemNoticeDismissRepositoryCustom {

    boolean existsByUserIdAndNoticeId(String userId, String noticeId);

    @Query("""
        SELECT d.noticeId FROM GroupSystemNoticeDismiss d
        WHERE d.userId = :userId AND d.noticeId IN :noticeIds
        """)
    List<String> findNoticeIdsByUserIdAndNoticeIdIn(
        @Param("userId") String userId,
        @Param("noticeIds") Collection<String> noticeIds);
}
