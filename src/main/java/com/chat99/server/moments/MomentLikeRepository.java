package com.chat99.server.moments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MomentLikeRepository extends JpaRepository<MomentLike, Long> {

    Optional<MomentLike> findByMomentIdAndUserId(String momentId, String userId);

    boolean existsByMomentIdAndUserId(String momentId, String userId);

    long countByMomentId(String momentId);

    List<MomentLike> findByMomentIdOrderByCreatedAtDesc(String momentId, Pageable pageable);

    List<MomentLike> findByMomentIdOrderByCreatedAtAsc(String momentId);

    List<MomentLike> findByMomentIdIn(Collection<String> momentIds);

    List<MomentLike> findByMomentIdInOrderByCreatedAtDesc(Collection<String> momentIds);

    void deleteByMomentIdAndUserId(String momentId, String userId);
}
