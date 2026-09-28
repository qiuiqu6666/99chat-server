package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SessionStatus;
import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
import com.chat99.server.user.UserDeviceAppService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncSessionService {

    private final SyncSessionRepository sessionRepository;
    private final UserSyncStateRepository syncStateRepository;
    private final UserDeviceSyncStateRepository deviceSyncStateRepository;
    private final UserDeviceAppService deviceAppService;

    public SyncSessionService(SyncSessionRepository sessionRepository,
                              UserSyncStateRepository syncStateRepository,
                              UserDeviceSyncStateRepository deviceSyncStateRepository,
                              UserDeviceAppService deviceAppService) {
        this.sessionRepository = sessionRepository;
        this.syncStateRepository = syncStateRepository;
        this.deviceSyncStateRepository = deviceSyncStateRepository;
        this.deviceAppService = deviceAppService;
    }

    @Transactional
    public SyncSession createSession(String userId, String deviceId, SyncType type, SyncMode mode) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String normalizedDeviceId = deviceId.trim();
        if (UserContactSource.LEGACY_DEVICE.equals(normalizedDeviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (!deviceAppService.deviceBelongsToUser(userId, normalizedDeviceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEVICE_NOT_BOUND");
        }
        SyncSession session = new SyncSession();
        session.setSessionUuid(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setDeviceId(normalizedDeviceId);
        session.setSyncType(type);
        session.setSyncMode(mode);
        session.setStatus(SessionStatus.RUNNING);
        return sessionRepository.save(session);
    }

    public SyncSession requireRunningSession(String userId, String sessionUuid, SyncType expectedType) {
        SyncSession session = loadOwned(userId, sessionUuid);
        if (session.getSyncType() != expectedType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SYNC_SESSION_TYPE_MISMATCH");
        }
        if (session.getStatus() != SessionStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SYNC_SESSION_NOT_RUNNING");
        }
        return session;
    }

    /** 相册/视频混合同步：接受 PHOTOS 或 VIDEOS 会话。 */
    public SyncSession requireRunningAlbumSession(String userId, String sessionUuid) {
        SyncSession session = loadOwned(userId, sessionUuid);
        if (session.getSyncType() != SyncType.PHOTOS && session.getSyncType() != SyncType.VIDEOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SYNC_SESSION_TYPE_MISMATCH");
        }
        if (session.getStatus() != SessionStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SYNC_SESSION_NOT_RUNNING");
        }
        return session;
    }

    @Transactional
    public SyncSession lockForComplete(String userId, String sessionUuid, SyncType expectedType, boolean album) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        SyncSession session = sessionRepository.lockBySessionUuidAndUserId(sessionUuid, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SYNC_SESSION_NOT_FOUND"));
        boolean typeOk = album
            ? session.getSyncType() == SyncType.PHOTOS || session.getSyncType() == SyncType.VIDEOS
            : session.getSyncType() == expectedType;
        if (!typeOk) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SYNC_SESSION_TYPE_MISMATCH");
        }
        if (session.getStatus() != SessionStatus.RUNNING && session.getStatus() != SessionStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SYNC_SESSION_NOT_RUNNING");
        }
        return session;
    }

    public void bumpUploadStatsIfRunning(String userId, String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return;
        }
        sessionRepository.findBySessionUuidAndUserId(sessionUuid, userId)
            .filter(session -> session.getStatus() == SessionStatus.RUNNING)
            .ifPresent(session -> addBatchStats(session, 1, 0, 0));
    }

    @Transactional
    public void addBatchStats(SyncSession session, int uploaded, int skipped, int failed) {
        session.setUploadedCount(session.getUploadedCount() + uploaded);
        session.setSkippedCount(session.getSkippedCount() + skipped);
        session.setFailedCount(session.getFailedCount() + failed);
        sessionRepository.save(session);
    }

    @Transactional
    public long markCompleted(SyncSession session, int deleted, boolean deviceScoped) {
        if (session.getStatus() == SessionStatus.COMPLETED) {
            return session.getCommittedRevision() == null ? 0L : session.getCommittedRevision();
        }
        session.setDeletedCount(deleted);
        session.setStatus(SessionStatus.COMPLETED);
        session.setCompletedAt(Instant.now());
        long revision = deviceScoped ? bumpDeviceState(session) : bumpAccountState(session);
        session.setCommittedRevision(revision);
        sessionRepository.save(session);
        return revision;
    }

    private SyncSession loadOwned(String userId, String sessionUuid) {
        return sessionRepository.findBySessionUuidAndUserId(sessionUuid, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SYNC_SESSION_NOT_FOUND"));
    }

    private long bumpAccountState(SyncSession session) {
        UserSyncState state = syncStateRepository.findByUserIdAndSyncType(session.getUserId(), session.getSyncType())
            .orElseGet(() -> {
                UserSyncState created = new UserSyncState();
                created.setUserId(session.getUserId());
                created.setSyncType(session.getSyncType());
                return created;
            });
        Instant now = Instant.now();
        if (session.getSyncMode() == SyncMode.FULL) {
            state.setLastFullSyncAt(now);
        } else {
            state.setLastIncrementalSyncAt(now);
        }
        state.setServerRevision(state.getServerRevision() + 1);
        syncStateRepository.save(state);
        return state.getServerRevision();
    }

    private long bumpDeviceState(SyncSession session) {
        UserDeviceSyncState state = deviceSyncStateRepository
            .findByUserIdAndDeviceIdAndSyncType(session.getUserId(), session.getDeviceId(), session.getSyncType())
            .orElseGet(() -> {
                UserDeviceSyncState created = new UserDeviceSyncState();
                created.setUserId(session.getUserId());
                created.setDeviceId(session.getDeviceId());
                created.setSyncType(session.getSyncType());
                return created;
            });
        Instant now = Instant.now();
        if (session.getSyncMode() == SyncMode.FULL) {
            state.setLastFullSyncAt(now);
            state.setBaselineReady(true);
        } else {
            state.setLastIncrementalSyncAt(now);
        }
        state.setServerRevision(state.getServerRevision() + 1);
        deviceSyncStateRepository.save(state);
        return state.getServerRevision();
    }
}
