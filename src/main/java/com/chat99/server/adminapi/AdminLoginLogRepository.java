package com.chat99.server.adminapi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminLoginLogRepository extends JpaRepository<AdminLoginLog, Long>,
    JpaSpecificationExecutor<AdminLoginLog> {

    java.util.Optional<AdminLoginLog> findTopByAdminUserIdAndSuccessTrueOrderByLoginAtDesc(Long adminUserId);
}
