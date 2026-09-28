package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SessionStatus;
import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContactSyncService {

    private final SyncSessionService sessionService;
    private final UserContactItemRepository contactRepository;
    private final UserContactSourceRepository sourceRepository;
    private final ContactSyncBatchRepository batchRepository;
    private final ContactSyncStagingRepository stagingRepository;
    private final UserDeviceSyncStateRepository deviceSyncStateRepository;
    private final SyncProperties props;
    private final ContactPlatformMatchService platformMatchService;
    private final ObjectMapper json = new ObjectMapper();

    public ContactSyncService(SyncSessionService sessionService,
                              UserContactItemRepository contactRepository,
                              UserContactSourceRepository sourceRepository,
                              ContactSyncBatchRepository batchRepository,
                              ContactSyncStagingRepository stagingRepository,
                              UserDeviceSyncStateRepository deviceSyncStateRepository,
                              SyncProperties props,
                              ContactPlatformMatchService platformMatchService) {
        this.sessionService = sessionService;
        this.contactRepository = contactRepository;
        this.sourceRepository = sourceRepository;
        this.batchRepository = batchRepository;
        this.stagingRepository = stagingRepository;
        this.deviceSyncStateRepository = deviceSyncStateRepository;
        this.props = props;
        this.platformMatchService = platformMatchService;
    }

    public record SessionResponse(String syncSessionId, String syncType, String syncMode, String status) {}

    public record ContactItemDto(
        String localContactId,
        String fingerprint,
        String displayName,
        List<String> phones,
        Long updatedAt) {}

    public record BatchRequest(
        String syncSessionId,
        List<ContactItemDto> items,
        Long baseRevision,
        String batchId,
        String payloadHash) {}

    public record ItemResult(String localContactId, String status) {}

    public record BatchResponse(String syncSessionId, List<ItemResult> results,
                                int uploaded, int skipped, int failed) {}

    public record CompleteRequest(
        String syncSessionId,
        List<String> deletedLocalContactIds,
        Long baseRevision,
        Boolean snapshotComplete) {}

    public record CompleteResponse(String syncSessionId, String status, int deleted, Long committedRevision) {}

    public record ContactView(
        String localContactId,
        String fingerprint,
        String displayName,
        List<String> phones,
        Instant syncedAt) {}

    private record StoredBatch(List<ItemResult> results, int uploaded, int skipped, int failed) {}

    static CompleteResponse completedReceipt(SyncSession session) {
        return new CompleteResponse(session.getSessionUuid(), "COMPLETED",
            session.getDeletedCount(), session.getCommittedRevision());
    }

    @Transactional
    public SessionResponse startSession(String userId, String deviceId, SyncMode mode) {
        SyncSession session = sessionService.createSession(userId, deviceId, SyncType.CONTACTS, mode);
        return new SessionResponse(session.getSessionUuid(), "CONTACTS", mode.name(), "RUNNING");
    }

    @Transactional
    public BatchResponse uploadBatch(String userId, BatchRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.items().size() > props.maxContactsBatch()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
        if (isV2Batch(req)) {
            return uploadBatchV2(userId, req);
        }
        SyncSession session = sessionService.requireRunningSession(userId, req.syncSessionId(), SyncType.CONTACTS);
        return writeFormalBatch(userId, session, req.items(), true);
    }

    @Transactional
    public CompleteResponse complete(String userId, CompleteRequest req) {
        SyncSession session = sessionService.lockForComplete(userId, req.syncSessionId(), SyncType.CONTACTS, false);
        if (session.getStatus() == SessionStatus.COMPLETED) {
            return completedReceipt(session);
        }
        if (isV2Complete(req)) {
            return completeV2(userId, session, req);
        }
        int deleted = deleteContacts(userId, req.deletedLocalContactIds());
        long revision = sessionService.markCompleted(session, deleted, false);
        return new CompleteResponse(session.getSessionUuid(), "COMPLETED", deleted, revision);
    }

    public List<ContactView> listContacts(String userId) {
        return contactRepository.findByUserIdAndStatusOrderByDisplayNameAsc(userId, 1).stream()
            .map(this::toView)
            .toList();
    }

    private BatchResponse uploadBatchV2(String userId, BatchRequest req) {
        requireV2BatchFields(req);
        SyncSession session = sessionService.lockForComplete(userId, req.syncSessionId(), SyncType.CONTACTS, false);
        if (session.getStatus() != SessionStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SYNC_SESSION_NOT_RUNNING");
        }
        DeviceBaseline baseline = deviceBaseline(userId, session.getDeviceId());
        if (req.baseRevision() != baseline.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_CONFLICT");
        }
        if (session.getSyncMode() == SyncMode.INCREMENTAL && !baseline.ready()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUIRE_FULL_SYNC");
        }
        ContactSyncBatch existing = batchRepository
            .findBySessionUuidAndBatchId(session.getSessionUuid(), req.batchId())
            .orElse(null);
        if (existing != null) {
            return replayBatch(session.getSessionUuid(), existing, req.payloadHash());
        }

        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        List<ItemResult> results = new ArrayList<>();
        for (ContactItemDto dto : req.items()) {
            try {
                validateItem(dto);
                boolean changed = stageContact(session, req.batchId(), dto);
                if (changed) {
                    uploaded++;
                    results.add(new ItemResult(dto.localContactId(), "UPLOADED"));
                } else {
                    skipped++;
                    results.add(new ItemResult(dto.localContactId(), "SKIPPED"));
                }
            } catch (Exception e) {
                failed++;
                results.add(new ItemResult(
                    dto.localContactId() == null ? "" : dto.localContactId(), "FAILED"));
            }
        }
        StoredBatch stored = new StoredBatch(results, uploaded, skipped, failed);
        ContactSyncBatch batch = new ContactSyncBatch();
        batch.setSessionUuid(session.getSessionUuid());
        batch.setBatchId(req.batchId());
        batch.setPayloadHash(req.payloadHash());
        try {
            batch.setResultJson(json.writeValueAsString(stored));
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "BATCH_STORE_FAILED");
        }
        batch.setUploaded(uploaded);
        batch.setSkipped(skipped);
        batch.setFailed(failed);
        try {
            batchRepository.saveAndFlush(batch);
        } catch (DataIntegrityViolationException dup) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BATCH_RETRY");
        }
        sessionService.addBatchStats(session, uploaded, skipped, failed);
        return new BatchResponse(session.getSessionUuid(), results, uploaded, skipped, failed);
    }

    private CompleteResponse completeV2(String userId, SyncSession session, CompleteRequest req) {
        if (req.baseRevision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (!Boolean.TRUE.equals(req.snapshotComplete())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SNAPSHOT_INCOMPLETE");
        }
        DeviceBaseline baseline = deviceBaseline(userId, session.getDeviceId());
        if (req.baseRevision() != baseline.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_CONFLICT");
        }
        if (session.getSyncMode() == SyncMode.INCREMENTAL && !baseline.ready()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUIRE_FULL_SYNC");
        }
        applyStaging(userId, session);
        int deleted = deleteDeviceSources(userId, session.getDeviceId(), req.deletedLocalContactIds());
        long revision = sessionService.markCompleted(session, deleted, true);
        return new CompleteResponse(session.getSessionUuid(), "COMPLETED", deleted, revision);
    }

    private BatchResponse writeFormalBatch(String userId, SyncSession session, List<ContactItemDto> items,
                                           boolean countStats) {
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        List<ItemResult> results = new ArrayList<>();
        Map<String, ContactPlatformMatchService.Match> platformMatches =
            platformMatchService.match(items.stream()
                .map(item -> new ContactPlatformMatchService.ContactCandidate(
                    item.localContactId(), userId, item.phones()))
                .toList());
        for (ContactItemDto dto : items) {
            try {
                validateItem(dto);
                boolean changed = upsertContact(userId, dto, platformMatches.get(dto.localContactId()),
                    UserContactSource.LEGACY_DEVICE);
                if (changed) {
                    uploaded++;
                    results.add(new ItemResult(dto.localContactId(), "UPLOADED"));
                } else {
                    skipped++;
                    results.add(new ItemResult(dto.localContactId(), "SKIPPED"));
                }
            } catch (Exception e) {
                failed++;
                results.add(new ItemResult(dto.localContactId(), "FAILED"));
            }
        }
        if (countStats) {
            sessionService.addBatchStats(session, uploaded, skipped, failed);
        }
        return new BatchResponse(session.getSessionUuid(), results, uploaded, skipped, failed);
    }

    private void applyStaging(String userId, SyncSession session) {
        List<ContactSyncStaging> staged = stagingRepository.findBySessionUuid(session.getSessionUuid());
        if (staged.isEmpty()) {
            return;
        }
        List<ContactItemDto> items = staged.stream()
            .map(row -> new ContactItemDto(row.getLocalContactId(), row.getFingerprint(), row.getDisplayName(),
                readPhones(row.getPhonesJson()),
                row.getContactUpdatedAt() == null ? null : row.getContactUpdatedAt().getEpochSecond()))
            .toList();
        Map<String, ContactPlatformMatchService.Match> platformMatches =
            platformMatchService.match(items.stream()
                .map(item -> new ContactPlatformMatchService.ContactCandidate(
                    item.localContactId(), userId, item.phones()))
                .toList());
        for (ContactItemDto dto : items) {
            try {
                upsertContact(userId, dto, platformMatches.get(dto.localContactId()), session.getDeviceId());
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "CONTACT_APPLY_FAILED");
            }
        }
    }

    private boolean stageContact(SyncSession session, String batchId, ContactItemDto dto)
        throws JsonProcessingException {
        String phonesJson = json.writeValueAsString(dto.phones() == null ? List.of() : dto.phones());
        ContactSyncStaging row = stagingRepository
            .findBySessionUuidAndLocalContactId(session.getSessionUuid(), dto.localContactId())
            .orElseGet(ContactSyncStaging::new);
        boolean changed = row.getId() == null || !dto.fingerprint().equals(row.getFingerprint());
        var formal = contactRepository.findByUserIdAndLocalContactId(session.getUserId(), dto.localContactId());
        if (formal.isPresent() && formal.get().getStatus() == 1
            && formal.get().getFingerprint().equals(dto.fingerprint())) {
            changed = false;
        }
        row.setSessionUuid(session.getSessionUuid());
        row.setBatchId(batchId);
        row.setLocalContactId(dto.localContactId());
        row.setFingerprint(dto.fingerprint());
        row.setDisplayName(dto.displayName());
        row.setPhonesJson(phonesJson);
        if (dto.updatedAt() != null) {
            row.setContactUpdatedAt(Instant.ofEpochSecond(dto.updatedAt()));
        }
        stagingRepository.save(row);
        return changed;
    }

    private int deleteContacts(String userId, List<String> localIds) {
        int deleted = 0;
        if (localIds == null) {
            return 0;
        }
        for (String localId : localIds) {
            if (localId == null || localId.isBlank()) {
                continue;
            }
            deleted += contactRepository.findByUserIdAndLocalContactId(userId, localId)
                .filter(c -> c.getStatus() == 1)
                .map(c -> {
                    c.setStatus(0);
                    contactRepository.save(c);
                    return 1;
                })
                .orElse(0);
        }
        return deleted;
    }

    private int deleteDeviceSources(String userId, String deviceId, List<String> localIds) {
        if (localIds == null || localIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String localId : localIds) {
            if (localId == null || localId.isBlank() || UserContactSource.LEGACY_DEVICE.equals(deviceId)) {
                continue;
            }
            sourceRepository.findByUserIdAndDeviceIdAndLocalContactId(userId, deviceId, localId)
                .ifPresent(sourceRepository::delete);
            sourceRepository.flush();
            if (sourceRepository.countByUserIdAndLocalContactId(userId, localId) > 0) {
                continue;
            }
            deleted += contactRepository.findByUserIdAndLocalContactId(userId, localId)
                .filter(c -> c.getStatus() == 1)
                .map(c -> {
                    c.setStatus(0);
                    contactRepository.save(c);
                    return 1;
                })
                .orElse(0);
        }
        return deleted;
    }

    private boolean upsertContact(String userId, ContactItemDto dto,
                                  ContactPlatformMatchService.Match match, String sourceDeviceId)
        throws JsonProcessingException {
        String phonesJson = json.writeValueAsString(dto.phones() == null ? List.of() : dto.phones());
        Instant now = Instant.now();
        var existing = contactRepository.findByUserIdAndLocalContactId(userId, dto.localContactId());
        UserContactItem row;
        boolean changed;
        if (existing.isPresent()) {
            row = existing.get();
            changed = !(row.getFingerprint().equals(dto.fingerprint()) && row.getStatus() == 1);
            row.setFingerprint(dto.fingerprint());
            row.setDisplayName(dto.displayName());
            row.setPhonesJson(phonesJson);
            row.setStatus(1);
            applyPlatformMatch(row, match);
            row.setSyncedAt(now);
            if (dto.updatedAt() != null) {
                row.setContactUpdatedAt(Instant.ofEpochSecond(dto.updatedAt()));
            }
        } else {
            row = new UserContactItem();
            row.setUserId(userId);
            row.setLocalContactId(dto.localContactId());
            row.setFingerprint(dto.fingerprint());
            row.setDisplayName(dto.displayName());
            row.setPhonesJson(phonesJson);
            row.setStatus(1);
            applyPlatformMatch(row, match);
            row.setSyncedAt(now);
            if (dto.updatedAt() != null) {
                row.setContactUpdatedAt(Instant.ofEpochSecond(dto.updatedAt()));
            }
            changed = true;
        }
        contactRepository.save(row);
        saveSource(userId, sourceDeviceId, row);
        return changed;
    }

    private void saveSource(String userId, String deviceId, UserContactItem row) {
        UserContactSource source = sourceRepository
            .findByUserIdAndDeviceIdAndLocalContactId(userId, deviceId, row.getLocalContactId())
            .orElseGet(UserContactSource::new);
        source.setUserId(userId);
        source.setDeviceId(deviceId);
        source.setLocalContactId(row.getLocalContactId());
        source.setContactItemId(row.getId());
        source.setFingerprint(row.getFingerprint());
        sourceRepository.save(source);
    }

    private DeviceBaseline deviceBaseline(String userId, String deviceId) {
        return deviceSyncStateRepository
            .findByUserIdAndDeviceIdAndSyncType(userId, deviceId, SyncType.CONTACTS)
            .map(state -> new DeviceBaseline(state.getServerRevision(), state.isBaselineReady()))
            .orElse(new DeviceBaseline(0L, false));
    }

    private BatchResponse replayBatch(String sessionUuid, ContactSyncBatch batch, String payloadHash) {
        if (!batch.getPayloadHash().equals(payloadHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BATCH_CONFLICT");
        }
        try {
            StoredBatch stored = json.readValue(batch.getResultJson(), StoredBatch.class);
            return new BatchResponse(sessionUuid, stored.results(), stored.uploaded(), stored.skipped(), stored.failed());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BATCH_CONFLICT");
        }
    }

    private static boolean isV2Batch(BatchRequest req) {
        boolean any = req.baseRevision() != null
            || (req.batchId() != null && !req.batchId().isBlank())
            || (req.payloadHash() != null && !req.payloadHash().isBlank());
        if (!any) {
            return false;
        }
        if (req.baseRevision() == null || req.batchId() == null || req.batchId().isBlank()
            || req.payloadHash() == null || req.payloadHash().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return true;
    }

    private static void requireV2BatchFields(BatchRequest req) {
        if (req.baseRevision() < 0 || req.batchId().length() > 64 || req.payloadHash().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    private static boolean isV2Complete(CompleteRequest req) {
        if (req.baseRevision() == null && req.snapshotComplete() == null) {
            return false;
        }
        if (req.baseRevision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return true;
    }

    private static void applyPlatformMatch(UserContactItem row, ContactPlatformMatchService.Match match) {
        boolean matched = match != null && match.platformUser();
        row.setPlatformUser(matched);
        row.setMatchedUserId(matched ? match.matchedUserId() : null);
    }

    private void validateItem(ContactItemDto dto) {
        if (dto.localContactId() == null || dto.localContactId().isBlank()
            || dto.fingerprint() == null || dto.fingerprint().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (dto.fingerprint().length() != 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FINGERPRINT");
        }
    }

    private List<String> readPhones(String phonesJson) {
        try {
            return json.readValue(phonesJson,
                json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private ContactView toView(UserContactItem row) {
        return new ContactView(row.getLocalContactId(), row.getFingerprint(),
            row.getDisplayName(), readPhones(row.getPhonesJson()), row.getSyncedAt());
    }

    private record DeviceBaseline(long revision, boolean ready) {}
}
