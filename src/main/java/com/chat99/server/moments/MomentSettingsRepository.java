package com.chat99.server.moments;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MomentSettingsRepository extends JpaRepository<MomentSettings, String> {

    Optional<MomentSettings> findByUserId(String userId);
}
