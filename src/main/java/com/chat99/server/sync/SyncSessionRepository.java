package com.chat99.server.sync;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncSessionRepository extends JpaRepository<SyncSession, Long> {

    Optional<SyncSession> findBySessionUuidAndUserId(String sessionUuid, String userId);
}
