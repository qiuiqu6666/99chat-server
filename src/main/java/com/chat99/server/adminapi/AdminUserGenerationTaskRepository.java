package com.chat99.server.adminapi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminUserGenerationTaskRepository
    extends JpaRepository<AdminUserGenerationTask, Long> {

    Optional<AdminUserGenerationTask> findByTaskNo(String taskNo);

    Optional<AdminUserGenerationTask> findFirstByStatusOrderByCreatedAtAsc(
        AdminUserGenerationStatus status);

    Optional<AdminUserGenerationTask> findFirstByStatusAndUpdatedAtBeforeOrderByCreatedAtAsc(
        AdminUserGenerationStatus status, Instant updatedBefore);

    @Query(value = "SELECT password_ciphertext FROM admin_user_generation_tasks WHERE id = :id",
        nativeQuery = true)
    String findPasswordCiphertextById(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AdminUserGenerationTask t SET t.status = :status, t.startedAt = :startedAt, "
        + "t.updatedAt = :now WHERE t.id = :id")
    int markStatus(@Param("id") Long id,
                   @Param("status") AdminUserGenerationStatus status,
                   @Param("startedAt") Instant startedAt,
                   @Param("now") Instant now);

    @Query("""
        SELECT t FROM AdminUserGenerationTask t
        WHERE (:taskNo IS NULL OR t.taskNo LIKE CONCAT('%', :taskNo, '%'))
          AND (:createdBy IS NULL OR t.createdBy LIKE CONCAT('%', :createdBy, '%'))
          AND (:status IS NULL OR t.status = :status)
          AND (:createdFrom IS NULL OR t.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR t.createdAt < :createdTo)
        """)
    Page<AdminUserGenerationTask> search(
        @Param("taskNo") String taskNo,
        @Param("createdBy") String createdBy,
        @Param("status") AdminUserGenerationStatus status,
        @Param("createdFrom") Instant createdFrom,
        @Param("createdTo") Instant createdTo,
        Pageable pageable);

    List<AdminUserGenerationTask> findByExpiresAtBefore(Instant expiresAt);
}
