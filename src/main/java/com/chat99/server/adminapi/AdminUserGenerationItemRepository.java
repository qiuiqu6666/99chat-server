package com.chat99.server.adminapi;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserGenerationItemRepository
    extends JpaRepository<AdminUserGenerationItem, Long> {

    List<AdminUserGenerationItem> findByTaskIdOrderByItemIndexAsc(Long taskId);

    Optional<AdminUserGenerationItem> findFirstByTaskIdAndStatusInOrderByItemIndexAsc(
        Long taskId, List<String> statuses);

    long countByTaskIdAndStatus(Long taskId, String status);

    void deleteByTaskId(Long taskId);
}
