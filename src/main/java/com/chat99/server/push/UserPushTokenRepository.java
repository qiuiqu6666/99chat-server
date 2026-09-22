package com.chat99.server.push;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPushTokenRepository extends JpaRepository<UserPushToken, Long> {

    List<UserPushToken> findByUserIdAndApnsEnabledTrue(String userId);

    List<UserPushToken> findByUserIdAndVoipEnabledTrue(String userId);

    List<UserPushToken> findByUserId(String userId);

    java.util.Optional<UserPushToken> findByUserIdAndDeviceId(String userId, String deviceId);
}
