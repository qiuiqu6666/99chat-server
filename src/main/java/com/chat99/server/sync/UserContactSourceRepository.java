package com.chat99.server.sync;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserContactSourceRepository extends JpaRepository<UserContactSource, Long> {

    Optional<UserContactSource> findByUserIdAndDeviceIdAndLocalContactId(
        String userId, String deviceId, String localContactId);

    long countByUserIdAndLocalContactId(String userId, String localContactId);
}
