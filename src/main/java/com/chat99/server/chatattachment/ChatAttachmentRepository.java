package com.chat99.server.chatattachment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, String> {

    Optional<ChatAttachment> findByAttachmentId(String attachmentId);

    List<ChatAttachment> findByParentAttachmentId(String parentAttachmentId);

    List<ChatAttachment> findByKindAndStatusAndThumbnailAttachmentIdIsNullAndParentAttachmentIdIsNull(
        ChatAttachmentKind kind, ChatAttachmentStatus status);

    List<ChatAttachment> findByStatus(ChatAttachmentStatus status);

    List<ChatAttachment> findByStatusAndExpiresAtBefore(ChatAttachmentStatus status, Instant before);

    List<ChatAttachment> findByStatusAndPurgeAfterBefore(ChatAttachmentStatus status, Instant before);

    List<ChatAttachment> findByStatusAndReadyAtBefore(ChatAttachmentStatus status, Instant before);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ChatAttachment a SET a.status = :toStatus
        WHERE a.attachmentId = :id AND a.status = :fromStatus
        """)
    int casStatus(@Param("id") String id,
                  @Param("fromStatus") ChatAttachmentStatus fromStatus,
                  @Param("toStatus") ChatAttachmentStatus toStatus);

    @Query("""
        SELECT a FROM ChatAttachment a
        WHERE a.status IN :statuses AND a.expiresAt IS NOT NULL AND a.expiresAt < :now
        """)
    List<ChatAttachment> findExpiredByStatuses(@Param("statuses") Collection<ChatAttachmentStatus> statuses,
                                               @Param("now") Instant now);
}
