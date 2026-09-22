package com.chat99.server.moments;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MomentSettingsBlockedViewerRepository extends JpaRepository<MomentSettingsBlockedViewer, Long> {

    List<MomentSettingsBlockedViewer> findByUserId(String userId);

    List<MomentSettingsBlockedViewer> findByUserIdIn(Collection<String> userIds);

    @Modifying
    @Query("DELETE FROM MomentSettingsBlockedViewer b WHERE b.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);

    boolean existsByUserIdAndBlockedUserId(String userId, String blockedUserId);
}
