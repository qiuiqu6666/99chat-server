package com.chat99.server.group;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupJoinApplicationDismissRepository
    extends JpaRepository<GroupJoinApplicationDismiss, GroupJoinApplicationDismissId>,
        GroupJoinApplicationDismissRepositoryCustom {

    boolean existsByUserIdAndApplicationId(String userId, Long applicationId);

    @Query("""
        SELECT d.applicationId FROM GroupJoinApplicationDismiss d
        WHERE d.userId = :userId AND d.applicationId IN :applicationIds
        """)
    List<Long> findApplicationIdsByUserIdAndApplicationIdIn(
        @Param("userId") String userId,
        @Param("applicationIds") Collection<Long> applicationIds);
}
