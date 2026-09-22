package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSyncStateRepository extends JpaRepository<UserSyncState, UserSyncStateId> {

    Optional<UserSyncState> findByUserIdAndSyncType(String userId, SyncType syncType);

    List<UserSyncState> findByUserId(String userId);
}
