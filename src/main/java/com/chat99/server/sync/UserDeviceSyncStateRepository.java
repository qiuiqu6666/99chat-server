package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceSyncStateRepository extends JpaRepository<UserDeviceSyncState, UserDeviceSyncStateId> {

    List<UserDeviceSyncState> findByUserIdAndDeviceId(String userId, String deviceId);

    Optional<UserDeviceSyncState> findByUserIdAndDeviceIdAndSyncType(
        String userId, String deviceId, SyncType syncType);
}
