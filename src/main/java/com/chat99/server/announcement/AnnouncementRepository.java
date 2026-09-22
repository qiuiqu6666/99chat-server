package com.chat99.server.announcement;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, String>,
    JpaSpecificationExecutor<Announcement> {

    List<Announcement> findByStatusOrderByUpdatedAtDesc(AnnouncementStatus status);

    List<Announcement> findAllByOrderByUpdatedAtDesc();

    List<Announcement> findByStatusAndPublishAtLessThanEqualOrderByPublishAtAsc(
        AnnouncementStatus status, Instant publishAt);

    List<Announcement> findByTypeAndStatusAndImPushStatusOrderByPublishAtAsc(
        AnnouncementType type, AnnouncementStatus status, AnnouncementImPushStatus imPushStatus);

    List<Announcement> findByTypeAndStatusAndImPushStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
        AnnouncementType type, AnnouncementStatus status, AnnouncementImPushStatus imPushStatus, Instant updatedAt);

    @Query("""
        SELECT a FROM Announcement a
        WHERE a.status = com.chat99.server.announcement.AnnouncementStatus.PUBLISHED
          AND (a.publishAt IS NULL OR a.publishAt <= :now)
          AND (a.expireAt IS NULL OR a.expireAt > :now)
          AND (a.type = com.chat99.server.announcement.AnnouncementType.GLOBAL
               OR (a.type = com.chat99.server.announcement.AnnouncementType.PERSONAL
                   AND a.targetUserId = :userId))
        ORDER BY a.priority DESC, a.publishAt DESC, a.createdAt DESC
        """)
    List<Announcement> findVisibleForUser(@Param("userId") String userId,
                                            @Param("now") Instant now,
                                            Pageable pageable);

    @Query("""
        SELECT a FROM Announcement a
        WHERE a.id = :id
          AND a.status = com.chat99.server.announcement.AnnouncementStatus.PUBLISHED
          AND (a.publishAt IS NULL OR a.publishAt <= :now)
          AND (a.expireAt IS NULL OR a.expireAt > :now)
          AND (a.type = com.chat99.server.announcement.AnnouncementType.GLOBAL
               OR (a.type = com.chat99.server.announcement.AnnouncementType.PERSONAL
                   AND a.targetUserId = :userId))
        """)
    Optional<Announcement> findVisibleForUserById(@Param("id") String id,
                                                  @Param("userId") String userId,
                                                  @Param("now") Instant now);
}
