package com.chat99.server.moments;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MomentRepository extends JpaRepository<Moment, Long> {

    Optional<Moment> findByMomentId(String momentId);

    Optional<Moment> findByAuthorUserIdAndIdempotencyKey(String authorUserId, String idempotencyKey);

    @Query("""
        SELECT m FROM Moment m
        WHERE m.status = 1
          AND m.authorUserId IN :authorUserIds
          AND (:cursorCreatedAt IS NULL
            OR m.createdAt < :cursorCreatedAt
            OR (m.createdAt = :cursorCreatedAt AND m.momentId < :cursorMomentId))
        ORDER BY m.createdAt DESC, m.momentId DESC
        """)
    List<Moment> findFeed(@Param("authorUserIds") Collection<String> authorUserIds,
                          @Param("cursorCreatedAt") Instant cursorCreatedAt,
                          @Param("cursorMomentId") String cursorMomentId,
                          Pageable pageable);

    @Query("""
        SELECT m FROM Moment m
        WHERE m.status = 1
          AND m.authorUserId = :authorUserId
          AND (:cursorCreatedAt IS NULL
            OR m.createdAt < :cursorCreatedAt
            OR (m.createdAt = :cursorCreatedAt AND m.momentId < :cursorMomentId))
        ORDER BY m.createdAt DESC, m.momentId DESC
        """)
    List<Moment> findUserMoments(@Param("authorUserId") String authorUserId,
                                 @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                 @Param("cursorMomentId") String cursorMomentId,
                                 Pageable pageable);
}
