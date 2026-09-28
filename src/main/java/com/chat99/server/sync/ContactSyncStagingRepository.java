package com.chat99.server.sync;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactSyncStagingRepository extends JpaRepository<ContactSyncStaging, Long> {

    Optional<ContactSyncStaging> findBySessionUuidAndLocalContactId(String sessionUuid, String localContactId);

    List<ContactSyncStaging> findBySessionUuid(String sessionUuid);
}
