package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContactSyncService {

    private final SyncSessionService sessionService;
    private final UserContactItemRepository contactRepository;
    private final SyncProperties props;
    private final ContactPlatformMatchService platformMatchService;
    private final ObjectMapper json = new ObjectMapper();

    public ContactSyncService(SyncSessionService sessionService,
                              UserContactItemRepository contactRepository,
                              SyncProperties props,
                              ContactPlatformMatchService platformMatchService) {
        this.sessionService = sessionService;
        this.contactRepository = contactRepository;
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

    public record BatchRequest(String syncSessionId, List<ContactItemDto> items) {}

    public record ItemResult(String localContactId, String status) {}

    public record BatchResponse(String syncSessionId, List<ItemResult> results,
                                int uploaded, int skipped, int failed) {}

    public record CompleteRequest(String syncSessionId, List<String> deletedLocalContactIds) {}

    public record CompleteResponse(String syncSessionId, String status, int deleted) {}

    public record ContactView(
        String localContactId,
        String fingerprint,
        String displayName,
        List<String> phones,
        Instant syncedAt) {}

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
        SyncSession session = sessionService.requireRunningSession(userId, req.syncSessionId(), SyncType.CONTACTS);

        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        List<ItemResult> results = new ArrayList<>();
        Map<String, ContactPlatformMatchService.Match> platformMatches =
            platformMatchService.match(req.items().stream()
                .map(item -> new ContactPlatformMatchService.ContactCandidate(
                    item.localContactId(), userId, item.phones()))
                .toList());

        for (ContactItemDto dto : req.items()) {
            try {
                validateItem(dto);
                boolean changed = upsertContact(
                    userId, dto, platformMatches.get(dto.localContactId()));
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

        sessionService.addBatchStats(session, uploaded, skipped, failed);
        return new BatchResponse(session.getSessionUuid(), results, uploaded, skipped, failed);
    }

    @Transactional
    public CompleteResponse complete(String userId, CompleteRequest req) {
        SyncSession session = sessionService.requireRunningSession(userId, req.syncSessionId(), SyncType.CONTACTS);
        int deleted = 0;
        if (req.deletedLocalContactIds() != null) {
            for (String localId : req.deletedLocalContactIds()) {
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
        }
        sessionService.completeSession(session, session.getSyncMode());
        return new CompleteResponse(session.getSessionUuid(), "COMPLETED", deleted);
    }

    public List<ContactView> listContacts(String userId) {
        return contactRepository.findByUserIdAndStatusOrderByDisplayNameAsc(userId, 1).stream()
            .map(this::toView)
            .toList();
    }

    private boolean upsertContact(String userId, ContactItemDto dto,
                                  ContactPlatformMatchService.Match match)
        throws JsonProcessingException {
        String phonesJson = json.writeValueAsString(dto.phones() == null ? List.of() : dto.phones());
        Instant now = Instant.now();
        var existing = contactRepository.findByUserIdAndLocalContactId(userId, dto.localContactId());
        if (existing.isPresent()) {
            UserContactItem row = existing.get();
            boolean same = row.getFingerprint().equals(dto.fingerprint())
                && row.getStatus() == 1;
            row.setFingerprint(dto.fingerprint());
            row.setDisplayName(dto.displayName());
            row.setPhonesJson(phonesJson);
            row.setStatus(1);
            applyPlatformMatch(row, match);
            row.setSyncedAt(now);
            if (dto.updatedAt() != null) {
                row.setContactUpdatedAt(Instant.ofEpochSecond(dto.updatedAt()));
            }
            contactRepository.save(row);
            return !same;
        }
        UserContactItem row = new UserContactItem();
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
        contactRepository.save(row);
        return true;
    }

    private static void applyPlatformMatch(
        UserContactItem row, ContactPlatformMatchService.Match match) {
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

    private ContactView toView(UserContactItem row) {
        List<String> phones;
        try {
            phones = json.readValue(row.getPhonesJson(),
                json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            phones = List.of();
        }
        return new ContactView(row.getLocalContactId(), row.getFingerprint(),
            row.getDisplayName(), phones, row.getSyncedAt());
    }
}
