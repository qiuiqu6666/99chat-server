package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskAction;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LifePaymentTaskRepository extends JpaRepository<LifePaymentTask, Long> {
    long countByStatusIn(List<TaskStatus> statuses);

    Optional<LifePaymentTask> findByTaskNo(String taskNo);

    Optional<LifePaymentTask> findFirstByOrderNoAndStatusInOrderByCreatedAtDesc(String orderNo, List<TaskStatus> statuses);

    List<LifePaymentTask> findByOrderNoAndStatusIn(String orderNo, List<TaskStatus> statuses);

    boolean existsByActiveAccountKey(String activeAccountKey);

    boolean existsByLockedByAndStatus(String lockedBy, TaskStatus status);

    @Query("""
        SELECT t FROM LifePaymentTask t
        WHERE t.status = :ready
          AND t.serviceType IN :serviceTypes
          AND t.attemptCount < t.maxAttempts
          AND (t.taskAction = :queryAction OR t.paymentStatus = 'paid')
        ORDER BY CASE WHEN t.serviceType = :mobile THEN 0 ELSE 1 END,
                 t.attemptCount ASC,
                 t.createdAt ASC
        """)
    List<LifePaymentTask> findClaimCandidates(@Param("ready") TaskStatus ready,
                                              @Param("serviceTypes") List<ServiceType> serviceTypes,
                                              @Param("queryAction") TaskAction queryAction,
                                              @Param("mobile") ServiceType mobile,
                                              Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE LifePaymentTask t
        SET t.status = :running,
            t.lockedBy = :workerId,
            t.lockedAt = :now,
            t.heartbeatAt = :now,
            t.attemptCount = t.attemptCount + 1,
            t.updatedAt = :now
        WHERE t.id = :id AND t.status = :ready
        """)
    int claimTask(@Param("id") Long id,
                  @Param("workerId") String workerId,
                  @Param("now") Instant now,
                  @Param("running") TaskStatus running,
                  @Param("ready") TaskStatus ready);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE LifePaymentTask t
        SET t.status = :ready,
            t.lockedBy = NULL,
            t.lockedAt = NULL,
            t.heartbeatAt = NULL,
            t.updatedAt = :now
        WHERE t.status = :running
          AND t.heartbeatAt < :deadline
          AND t.attemptCount < t.maxAttempts
        """)
    int recoverTimedOutTasks(@Param("deadline") Instant deadline,
                             @Param("now") Instant now,
                             @Param("ready") TaskStatus ready,
                             @Param("running") TaskStatus running);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE LifePaymentTask t
        SET t.status = :needManual,
            t.activeAccountKey = NULL,
            t.updatedAt = :now,
            t.finishedAt = :now,
            t.lastErrorCode = 'heartbeat_timeout',
            t.lastErrorMessage = 'heartbeat timeout exceeded max attempts'
        WHERE t.status = :running
          AND t.heartbeatAt < :deadline
          AND t.attemptCount >= t.maxAttempts
        """)
    int markTimedOutNeedManual(@Param("deadline") Instant deadline,
                               @Param("now") Instant now,
                               @Param("needManual") TaskStatus needManual,
                               @Param("running") TaskStatus running);

    List<LifePaymentTask> findByStatusAndHeartbeatAtBefore(TaskStatus status, Instant heartbeatAt);

    @Query("""
        SELECT t FROM LifePaymentTask t
        WHERE (:taskNo IS NULL OR :taskNo = '' OR t.taskNo = :taskNo)
          AND (:orderNo IS NULL OR :orderNo = '' OR t.orderNo = :orderNo)
          AND (:serviceType IS NULL OR t.serviceType = :serviceType)
          AND (:status IS NULL OR t.status = :status)
        ORDER BY t.createdAt DESC
        """)
    org.springframework.data.domain.Page<LifePaymentTask> adminSearch(
        @Param("taskNo") String taskNo,
        @Param("orderNo") String orderNo,
        @Param("serviceType") ServiceType serviceType,
        @Param("status") TaskStatus status,
        Pageable pageable);
}
