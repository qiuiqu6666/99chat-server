package com.chat99.server.call;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallCallbackLogRepository extends JpaRepository<CallCallbackLog, Long> {
    boolean existsByIdempotencyKey(String idempotencyKey);

    List<CallCallbackLog> findByPayloadJsonContaining(String fragment);
}
