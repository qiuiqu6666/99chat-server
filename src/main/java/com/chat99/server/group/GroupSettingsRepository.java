package com.chat99.server.group;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupSettingsRepository extends JpaRepository<GroupSettings, String> {

    long countByCreatedAtGreaterThanEqual(java.time.Instant createdAt);
}
