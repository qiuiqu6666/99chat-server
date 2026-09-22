package com.chat99.server.announcement;

import java.util.Collection;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, AnnouncementReadId> {

    boolean existsByIdUserIdAndIdAnnouncementId(String userId, String announcementId);

    @Query("""
        SELECT r.id.announcementId FROM AnnouncementRead r
        WHERE r.id.userId = :userId AND r.id.announcementId IN :ids
        """)
    Set<String> findReadAnnouncementIds(@Param("userId") String userId,
                                        @Param("ids") Collection<String> ids);
}
