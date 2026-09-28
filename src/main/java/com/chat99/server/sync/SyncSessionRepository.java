package com.chat99.server.sync;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncSessionRepository extends JpaRepository<SyncSession, Long> {

    Optional<SyncSession> findBySessionUuidAndUserId(String sessionUuid, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SyncSession s WHERE s.sessionUuid = :sessionUuid AND s.userId = :userId")
    Optional<SyncSession> lockBySessionUuidAndUserId(@Param("sessionUuid") String sessionUuid,
                                                     @Param("userId") String userId);
}
