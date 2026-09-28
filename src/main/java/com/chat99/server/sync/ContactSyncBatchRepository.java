package com.chat99.server.sync;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactSyncBatchRepository extends JpaRepository<ContactSyncBatch, Long> {

    Optional<ContactSyncBatch> findBySessionUuidAndBatchId(String sessionUuid, String batchId);
}
