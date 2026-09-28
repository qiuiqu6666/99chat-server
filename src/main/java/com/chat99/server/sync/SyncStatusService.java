package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncType;
import com.chat99.server.user.UserDeviceAppService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncStatusService {

    private final UserSyncStateRepository syncStateRepository;
    private final UserDeviceSyncStateRepository deviceSyncStateRepository;
    private final UserDeviceAppService deviceAppService;

    public SyncStatusService(UserSyncStateRepository syncStateRepository,
                             UserDeviceSyncStateRepository deviceSyncStateRepository,
                             UserDeviceAppService deviceAppService) {
        this.syncStateRepository = syncStateRepository;
        this.deviceSyncStateRepository = deviceSyncStateRepository;
        this.deviceAppService = deviceAppService;
    }

    public record TypeStatus(
        String syncType,
        Instant lastFullSyncAt,
        Instant lastIncrementalSyncAt,
        long serverRevision) {}

    public record StatusResponse(List<TypeStatus> types, boolean contactsDeltaV2) {}

    public StatusResponse getStatus(String userId, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            return deviceStatus(userId, deviceId.trim());
        }
        Map<SyncType, UserSyncState> byType = new EnumMap<>(SyncType.class);
        for (UserSyncState state : syncStateRepository.findByUserId(userId)) {
            if (state.getSyncType() != null) {
                byType.put(state.getSyncType(), state);
            }
        }
        List<TypeStatus> list = new ArrayList<>();
        for (SyncType type : SyncType.values()) {
            UserSyncState state = byType.get(type);
            if (state != null) {
                list.add(new TypeStatus(type.name(), state.getLastFullSyncAt(),
                    state.getLastIncrementalSyncAt(), state.getServerRevision()));
            }
        }
        return new StatusResponse(list, true);
    }

    private StatusResponse deviceStatus(String userId, String deviceId) {
        if (!deviceAppService.deviceBelongsToUser(userId, deviceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEVICE_NOT_BOUND");
        }
        Map<SyncType, UserDeviceSyncState> byType = new EnumMap<>(SyncType.class);
        for (UserDeviceSyncState state : deviceSyncStateRepository.findByUserIdAndDeviceId(userId, deviceId)) {
            if (state.getSyncType() != null) {
                byType.put(state.getSyncType(), state);
            }
        }
        List<TypeStatus> list = new ArrayList<>();
        for (SyncType type : SyncType.values()) {
            UserDeviceSyncState state = byType.get(type);
            if (state != null) {
                list.add(new TypeStatus(type.name(), state.getLastFullSyncAt(),
                    state.getLastIncrementalSyncAt(), state.getServerRevision()));
            } else {
                list.add(new TypeStatus(type.name(), null, null, 0L));
            }
        }
        return new StatusResponse(list, true);
    }
}
