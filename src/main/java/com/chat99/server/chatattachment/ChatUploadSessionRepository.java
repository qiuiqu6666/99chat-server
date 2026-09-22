package com.chat99.server.chatattachment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatUploadSessionRepository extends JpaRepository<ChatUploadSession, String> {

    Optional<ChatUploadSession> findByUploadId(String uploadId);

    Optional<ChatUploadSession> findByUploadIdAndOwnerUserId(String uploadId, String ownerUserId);

    Optional<ChatUploadSession> findByOwnerUserIdAndClientUploadKey(String ownerUserId, String clientUploadKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ChatUploadSession s WHERE s.uploadId = :uploadId")
    Optional<ChatUploadSession> findByUploadIdForUpdate(@Param("uploadId") String uploadId);

    long countByOwnerUserIdAndParentUploadIdIsNullAndStatusInAndExpiresAtAfter(
        String ownerUserId, Collection<ChatUploadStatus> statuses, Instant now);

    List<ChatUploadSession> findByStatusInAndExpiresAtBefore(
        Collection<ChatUploadStatus> statuses, Instant before);

    List<ChatUploadSession> findByParentUploadId(String parentUploadId);

    Optional<ChatUploadSession> findFirstByAttachmentIdAndParentUploadIdIsNull(String attachmentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ChatUploadSession s SET s.status = :toStatus, s.updatedAt = CURRENT_TIMESTAMP
        WHERE s.uploadId = :id AND s.status IN :fromStatuses
        """)
    int casStatus(@Param("id") String id,
                  @Param("fromStatuses") Collection<ChatUploadStatus> fromStatuses,
                  @Param("toStatus") ChatUploadStatus toStatus);
}
