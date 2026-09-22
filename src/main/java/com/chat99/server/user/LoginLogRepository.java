package com.chat99.server.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginLogRepository extends JpaRepository<LoginLog, Long>, JpaSpecificationExecutor<LoginLog> {
    List<LoginLog> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        String userId, Instant from, Instant to, Pageable pageable);

    List<LoginLog> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserId(String userId);

    Optional<LoginLog> findFirstByUserIdAndSuccessTrueOrderByCreatedAtDesc(String userId);

    Optional<LoginLog> findFirstByUserIdOrderByCreatedAtAsc(String userId);

    @Query("SELECT DISTINCT l.ip FROM LoginLog l WHERE l.userId = :userId AND l.success = true "
        + "AND l.ip IS NOT NULL AND l.ip <> ''")
    List<String> findDistinctSuccessIpsByUserId(@Param("userId") String userId);

    @Query("SELECT DISTINCT l.userId FROM LoginLog l WHERE l.ip = :ip AND l.userId <> :excludeUserId "
        + "AND l.success = true AND l.userId IS NOT NULL")
    List<String> findDistinctUserIdsByIpExcluding(
        @Param("ip") String ip, @Param("excludeUserId") String excludeUserId, Pageable pageable);

    @Query("SELECT DISTINCT l.userId FROM LoginLog l WHERE l.deviceId = :deviceId AND l.userId <> :excludeUserId "
        + "AND l.success = true AND l.userId IS NOT NULL")
    List<String> findDistinctUserIdsByDeviceExcluding(
        @Param("deviceId") String deviceId, @Param("excludeUserId") String excludeUserId, Pageable pageable);

    @Query("SELECT DISTINCT l.deviceId FROM LoginLog l WHERE l.userId = :userId AND l.deviceId IS NOT NULL "
        + "AND l.deviceId <> ''")
    List<String> findDistinctDeviceIdsByUserId(@Param("userId") String userId);

    @Query("SELECT DISTINCT l.clientVersion FROM LoginLog l WHERE l.userId = :userId AND l.success = true "
        + "AND l.clientVersion IS NOT NULL AND l.clientVersion <> ''")
    List<String> findDistinctSuccessVersionsByUserId(@Param("userId") String userId);

    @Query("SELECT DISTINCT l.clientPlatform FROM LoginLog l WHERE l.userId = :userId AND l.success = true "
        + "AND l.clientPlatform IS NOT NULL AND l.clientPlatform <> ''")
    List<String> findDistinctSuccessPlatformsByUserId(@Param("userId") String userId);

    @Query("SELECT COUNT(DISTINCT l.userId) FROM LoginLog l WHERE l.success = true "
        + "AND l.createdAt >= :start AND l.createdAt < :end")
    long countDistinctSuccessfulLoginsBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT COUNT(l) FROM LoginLog l WHERE l.success = false "
        + "AND l.createdAt >= :start AND l.createdAt < :end")
    long countFailedLoginsBetween(@Param("start") Instant start, @Param("end") Instant end);

    List<LoginLog> findBySuccessTrueAndCreatedAtGreaterThanEqual(Instant start);

    Optional<LoginLog> findFirstByUserIdAndDeviceIdOrderByCreatedAtDesc(String userId, String deviceId);

    @Query("SELECT DISTINCT l.userId FROM LoginLog l WHERE l.deviceId = :deviceId "
        + "AND l.success = true AND l.userId IS NOT NULL")
    List<String> findDistinctUserIdsByDeviceId(@Param("deviceId") String deviceId, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT l.userId) FROM LoginLog l WHERE l.deviceId = :deviceId "
        + "AND l.success = true AND l.userId IS NOT NULL")
    long countDistinctUserIdsByDeviceId(@Param("deviceId") String deviceId);

    Page<LoginLog> findByUserIdAndSuccessTrueOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndSuccessTrue(String userId);
}
