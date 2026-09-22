package com.chat99.server.chatattachment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatAttachmentReferenceRepository extends JpaRepository<ChatAttachmentReference, String> {

    Optional<ChatAttachmentReference> findByReferenceId(String referenceId);

    Optional<ChatAttachmentReference> findByOwnerUserIdAndClientOperationIdAndAttachmentId(
        String ownerUserId, String clientOperationId, String attachmentId);

    List<ChatAttachmentReference> findByAttachmentId(String attachmentId);

    boolean existsByAttachmentIdAndStateIn(String attachmentId, Collection<ChatReferenceState> states);

    List<ChatAttachmentReference> findByStateAndExpiresAtBefore(ChatReferenceState state, Instant before);

    @Query("""
        SELECT r FROM ChatAttachmentReference r
        WHERE r.attachmentId = :attachmentId
          AND r.state IN :states
          AND (r.ownerUserId = :userId
               OR r.participantLow = :userId
               OR r.participantHigh = :userId)
        """)
    List<ChatAttachmentReference> findAccessibleByAttachment(
        @Param("attachmentId") String attachmentId,
        @Param("userId") String userId,
        @Param("states") Collection<ChatReferenceState> states);
}
