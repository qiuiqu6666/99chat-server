package com.chat99.server.moments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MomentMediaRepository extends JpaRepository<MomentMedia, Long> {

    Optional<MomentMedia> findByMediaId(String mediaId);

    Optional<MomentMedia> findByOwnerUserIdAndClientMediaId(String ownerUserId, String clientMediaId);

    List<MomentMedia> findByMediaIdIn(Collection<String> mediaIds);

    List<MomentMedia> findByMomentIdIn(Collection<String> momentIds);

    List<MomentMedia> findByMomentIdOrderByIdAsc(String momentId);
}
