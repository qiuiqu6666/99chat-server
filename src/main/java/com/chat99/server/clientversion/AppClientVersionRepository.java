package com.chat99.server.clientversion;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AppClientVersionRepository extends JpaRepository<AppClientVersion, Long>,
    JpaSpecificationExecutor<AppClientVersion> {

    Optional<AppClientVersion> findByPlatformAndVersion(ClientVersionPlatform platform, String version);

    boolean existsByPlatformAndVersionAndIdNot(ClientVersionPlatform platform, String version, Long id);

    Optional<AppClientVersion> findFirstByPlatformAndEnabledTrueOrderByPublishedAtDescIdDesc(
        ClientVersionPlatform platform);
}
