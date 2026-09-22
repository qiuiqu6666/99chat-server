package com.chat99.server.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {
    Optional<UserDevice> findByUserIdAndDeviceId(String userId, String deviceId);

    List<UserDevice> findByUserIdAndTrustedTrue(String userId);

    List<UserDevice> findByUserIdOrderByLastLoginAtDesc(String userId);

    @Modifying
    @Query("update UserDevice d set d.trusted = false where d.userId = :userId and d.trusted = true")
    int clearTrustedByUserId(@Param("userId") String userId);

    @Modifying
    @Query("update UserDevice d set d.trusted = false where d.deviceId = :deviceId and d.trusted = true")
    int clearTrustedByDeviceId(@Param("deviceId") String deviceId);

    Optional<UserDevice> findFirstByDeviceIdOrderByLastLoginAtDesc(String deviceId);

    @Query("""
        SELECT d FROM UserDevice d
        WHERE (:userUid IS NULL OR d.userId = :userUid)
          AND (:deviceId IS NULL OR d.deviceId LIKE CONCAT('%', :deviceId, '%'))
          AND (:platform IS NULL OR LOWER(COALESCE(d.platform, '')) LIKE CONCAT('%', :platform, '%'))
          AND (:keyword IS NULL OR LOWER(COALESCE(d.model, '')) LIKE CONCAT('%', :keyword, '%')
               OR LOWER(COALESCE(d.platform, '')) LIKE CONCAT('%', :keyword, '%')
               OR LOWER(d.deviceId) LIKE CONCAT('%', :keyword, '%'))
        ORDER BY d.lastLoginAt DESC, d.createdAt DESC
        """)
    Page<UserDevice> searchDevices(
        @Param("userUid") String userUid,
        @Param("deviceId") String deviceId,
        @Param("platform") String platform,
        @Param("keyword") String keyword,
        Pageable pageable);
}
