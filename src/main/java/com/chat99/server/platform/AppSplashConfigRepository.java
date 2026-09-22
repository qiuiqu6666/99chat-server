package com.chat99.server.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AppSplashConfigRepository
    extends JpaRepository<AppSplashConfig, Long>, JpaSpecificationExecutor<AppSplashConfig> {

    List<AppSplashConfig> findByEnabledTrueOrderByUpdatedAtDesc();

    Optional<AppSplashConfig> findTopByVersionStartingWithOrderByVersionDesc(String prefix);

    boolean existsByVersion(String version);
}
