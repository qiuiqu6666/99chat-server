package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SessionStatus;
import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
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

    public SyncSessionService(SyncSessionRepository sessionRepository,
                              UserSyncStateRepository syncStateRepository) {
        this.sessionRepository = sessionRepository;
        this.syncStateRepository = syncStateRepository;
    }

    @Transactional
    public SyncSession createSession(String userId, String deviceId, SyncType type, SyncMode mode) {
        SyncSession session = new SyncSession();
        session.setSessionUuid(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setSyncType(type);
        session.setSyncMode(mode);
        session.setStatus(SessionStatus.RUNNING);
        return sessionRepository.save(session);
    }

    public SyncSession requireRunningSession(String userId, String sessionUuid, SyncType expectedType) {
        SyncSession session = sessionRepository.findBySessionUuidAndUserId(sessionUuid, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SYNC_SESSION_NOT_FOUND"));
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
        SyncSession session = sessionRepository.findBySessionUuidAndUserId(sessionUuid, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SYNC_SESSION_NOT_FOUND"));
        if (session.getSyncType() != SyncType.PHOTOS && session.getSyncType() != SyncType.VIDEOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SYNC_SESSION_TYPE_MISMATCH");
        }
        if (session.getStatus() != SessionStatus.RUNNING) {
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
    public void completeSession(SyncSession session, SyncMode mode) {
        session.setStatus(SessionStatus.COMPLETED);
        session.setCompletedAt(Instant.now());
        sessionRepository.save(session);

        UserSyncState state = syncStateRepository.findByUserIdAndSyncType(session.getUserId(), session.getSyncType())
            .orElseGet(() -> {
                UserSyncState s = new UserSyncState();
                s.setUserId(session.getUserId());
                s.setSyncType(session.getSyncType());
                return s;
            });
        Instant now = Instant.now();
        if (mode == SyncMode.FULL) {
            state.setLastFullSyncAt(now);
        } else {
            state.setLastIncrementalSyncAt(now);
        }
        state.setServerRevision(state.getServerRevision() + 1);
        syncStateRepository.save(state);
    }
}
