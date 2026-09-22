package com.chat99.server.adminapi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long>,
    JpaSpecificationExecutor<AdminAuditLog> {
}
