package com.chat99.server.sync;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserPhotoRepository extends JpaRepository<UserPhoto, Long>,
    JpaSpecificationExecutor<UserPhoto> {

    Optional<UserPhoto> findByPhotoUuidAndUserId(String photoUuid, String userId);

    Optional<UserPhoto> findByPhotoUuid(String photoUuid);

    Optional<UserPhoto> findByUserIdAndContentHashAndStatus(String userId, String contentHash, int status);

    Optional<UserPhoto> findByUserIdAndLocalAssetIdAndStatus(String userId, String localAssetId, int status);

    List<UserPhoto> findByUserIdAndStatusOrderByTakenAtDesc(String userId, int status, Pageable pageable);

    List<UserPhoto> findByUserIdAndMediaTypeAndStatusOrderByTakenAtDesc(
        String userId, String mediaType, int status, Pageable pageable);

    @Query("""
        SELECT p FROM UserPhoto p
        WHERE p.userId = :userId AND p.status = :status
          AND (p.mediaType IS NULL OR p.mediaType = 'IMAGE')
          AND (p.mimeType IS NULL OR LOWER(p.mimeType) NOT LIKE 'video/%')
        ORDER BY p.takenAt DESC
        """)
    List<UserPhoto> findImagesByUserId(@Param("userId") String userId,
                                       @Param("status") int status,
                                       Pageable pageable);

    @Modifying
    @Transactional
    @Query("""
        UPDATE UserPhoto p
        SET p.ossThumbKey = :thumbKey, p.thumbUrl = :thumbUrl
        WHERE p.status = 1 AND p.mediaType = 'VIDEO'
          AND (p.thumbUrl IS NULL OR p.thumbUrl = '')
        """)
    int backfillMissingVideoThumb(@Param("thumbKey") String thumbKey,
                                  @Param("thumbUrl") String thumbUrl);
}
