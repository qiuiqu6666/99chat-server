package com.chat99.server.chatattachment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatUserQuotaRepository extends JpaRepository<ChatUserQuota, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM ChatUserQuota q WHERE q.userId = :userId")
    Optional<ChatUserQuota> findByUserIdForUpdate(@Param("userId") String userId);
}
