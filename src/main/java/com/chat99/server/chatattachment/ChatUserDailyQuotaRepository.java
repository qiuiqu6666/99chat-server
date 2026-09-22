package com.chat99.server.chatattachment;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatUserDailyQuotaRepository extends JpaRepository<ChatUserDailyQuota, ChatUserDailyQuotaId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM ChatUserDailyQuota q WHERE q.userId = :userId AND q.quotaDay = :quotaDay")
    Optional<ChatUserDailyQuota> findForUpdate(@Param("userId") String userId,
                                               @Param("quotaDay") LocalDate quotaDay);
}
