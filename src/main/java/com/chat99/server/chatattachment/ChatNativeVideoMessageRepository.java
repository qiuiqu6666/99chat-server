package com.chat99.server.chatattachment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatNativeVideoMessageRepository extends JpaRepository<ChatNativeVideoMessage, String> {

    Optional<ChatNativeVideoMessage> findBySenderUserIdAndClientOperationId(
        String senderUserId, String clientOperationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ChatNativeVideoMessage m WHERE m.senderUserId = :sender AND m.clientOperationId = :op")
    Optional<ChatNativeVideoMessage> findForUpdate(@Param("sender") String senderUserId,
                                                   @Param("op") String clientOperationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ChatNativeVideoMessage m WHERE m.operationId = :id")
    Optional<ChatNativeVideoMessage> findByIdForUpdate(@Param("id") String operationId);

    @Query("""
        SELECT m FROM ChatNativeVideoMessage m
        WHERE m.status IN :statuses
          AND (m.lockUntil IS NULL OR m.lockUntil < :now)
        ORDER BY m.updatedAt ASC
        """)
    java.util.List<ChatNativeVideoMessage> findDue(
        @Param("statuses") java.util.Collection<ChatNativeVideoStatus> statuses,
        @Param("now") java.time.Instant now,
        org.springframework.data.domain.Pageable pageable);
}
