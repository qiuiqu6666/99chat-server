package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SyncStatusService {

    private final UserSyncStateRepository syncStateRepository;

    public SyncStatusService(UserSyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    public record TypeStatus(
        String syncType,
        Instant lastFullSyncAt,
        Instant lastIncrementalSyncAt,
        long serverRevision) {}

    public record StatusResponse(List<TypeStatus> types) {}

    public StatusResponse getStatus(String userId) {
        Map<SyncType, UserSyncState> byType = new EnumMap<>(SyncType.class);
        for (UserSyncState state : syncStateRepository.findByUserId(userId)) {
            if (state.getSyncType() != null) {
                byType.put(state.getSyncType(), state);
            }
        }
        List<TypeStatus> list = new ArrayList<>();
        for (SyncType type : SyncType.values()) {
            UserSyncState s = byType.get(type);
            if (s != null) {
                list.add(new TypeStatus(type.name(), s.getLastFullSyncAt(),
                    s.getLastIncrementalSyncAt(), s.getServerRevision()));
            }
        }
        return new StatusResponse(list);
    }
}
