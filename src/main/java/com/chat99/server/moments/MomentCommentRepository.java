package com.chat99.server.moments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MomentCommentRepository extends JpaRepository<MomentComment, Long> {

    Optional<MomentComment> findByCommentId(String commentId);

    Optional<MomentComment> findByMomentIdAndAuthorUserIdAndIdempotencyKey(String momentId, String authorUserId,
                                                                           String idempotencyKey);

    long countByMomentIdAndStatus(String momentId, int status);

    List<MomentComment> findByMomentIdAndStatusOrderByCreatedAtAsc(String momentId, int status);

    List<MomentComment> findByMomentIdAndStatusOrderByCreatedAtAsc(String momentId, int status, Pageable pageable);

    List<MomentComment> findByMomentIdInAndStatusOrderByCreatedAtAsc(Collection<String> momentIds, int status);
}
