package com.chat99.server.user;

import com.chat99.server.realtime.FriendRequestRealtimePublisher;
import com.chat99.server.user.FriendApplicationHistory.AddSource;
import com.chat99.server.user.FriendRequest.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FriendRequestService {

    public record RequestItem(
        Long id,
        String fromUserId,
        String toUserId,
        String peerUserId,
        String peerNickname,
        String peerAvatarUrl,
        String addWording,
        String addSource,
        String status,
        Instant createdAt,
        Instant handledAt) {}

    public record RequestListResponse(List<RequestItem> items) {}

    public record CreateRequestResult(String outcome, Long requestId) {}

    public record DeleteResponse(Long id, boolean deleted) {}

    public record BatchDeleteResponse(int deleted) {}

    private final FriendRequestRepository requestRepository;
    private final FriendApplicationRepository historyRepository;
    private final UserFriendService friendService;
    private final UserPrivacyService privacyService;
    private final FriendRequestRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final FriendRequestRealtimePublisher realtimePublisher;
    private final UserBlockService blockService;

    public FriendRequestService(FriendRequestRepository requestRepository,
                                FriendApplicationRepository historyRepository,
                                UserFriendService friendService,
                                UserPrivacyService privacyService,
                                FriendRequestRateLimiter rateLimiter,
                                UserRepository userRepository,
                                FriendRequestRealtimePublisher realtimePublisher,
                                UserBlockService blockService) {
        this.requestRepository = requestRepository;
        this.historyRepository = historyRepository;
        this.friendService = friendService;
        this.privacyService = privacyService;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.realtimePublisher = realtimePublisher;
        this.blockService = blockService;
    }

    public RequestListResponse listIncoming(String userId, int limit) {
        int safeLimit = clampLimit(limit);
        List<FriendRequest> rows = requestRepository
            .findByToUserIdAndStatusOrderByCreatedAtDesc(userId, Status.pending, PageRequest.of(0, safeLimit));
        return toListResponse(rows, true);
    }

    public RequestListResponse listOutgoing(String userId, int limit) {
        int safeLimit = clampLimit(limit);
        List<FriendRequest> rows = requestRepository
            .findByFromUserIdAndStatusOrderByCreatedAtDesc(userId, Status.pending, PageRequest.of(0, safeLimit));
        return toListResponse(rows, false);
    }

    /** 我发起的加好友记录（pending / accepted / rejected）。 */
    public RequestListResponse listSent(String userId, int limit) {
        int safeLimit = clampLimit(limit);
        List<FriendRequest> rows = requestRepository
            .findByFromUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, safeLimit));
        return toListResponse(rows, false);
    }

    @Transactional
    public DeleteResponse deleteIncoming(String userId, long id) {
        FriendRequest req = requestRepository.findByIdAndToUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        deleteRequest(req);
        return new DeleteResponse(id, true);
    }

    @Transactional
    public BatchDeleteResponse deleteIncomingBatch(String userId, List<Long> ids) {
        return deleteBatch(ids, requestRepository.findByIdInAndToUserId(ids, userId));
    }

    @Transactional
    public DeleteResponse deleteSent(String userId, long id) {
        FriendRequest req = requestRepository.findByIdAndFromUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        deleteRequest(req);
        return new DeleteResponse(id, true);
    }

    @Transactional
    public BatchDeleteResponse deleteSentBatch(String userId, List<Long> ids) {
        return deleteBatch(ids, requestRepository.findByIdInAndFromUserId(ids, userId));
    }

    @Transactional
    public CreateRequestResult createRequest(String fromUserId, String toUserId, String addWording, String addSourceRaw) {
        if (fromUserId.equals(toUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        friendService.requireActiveUser(fromUserId);
        friendService.requireUserExists(toUserId);
        assertNotBlocked(fromUserId, toUserId);

        AddSource addSource = parseAddSource(addSourceRaw);
        var privacy = privacyService.checkAddFriendBySource(toUserId, addSource.name());
        if (!privacy.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, privacy.reason());
        }

        if (friendService.isMutualActive(fromUserId, toUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_FRIENDS");
        }

        var reversePending = requestRepository.findByFromUserIdAndToUserIdAndStatus(
            toUserId, fromUserId, Status.pending);
        if (reversePending.isPresent()) {
            return autoAcceptPair(fromUserId, toUserId, reversePending.get(), addWording, addSource);
        }

        var existing = requestRepository.findByFromUserIdAndToUserIdAndStatus(fromUserId, toUserId, Status.pending);
        if (existing.isPresent()) {
            return new CreateRequestResult("pending", existing.get().getId());
        }

        rateLimiter.checkAndMark(fromUserId, toUserId);

        User target = userRepository.findByUserId(toUserId).orElseThrow();
        if (!target.isFriendAddRequiresVerify()) {
            return autoAcceptIncoming(fromUserId, toUserId, addWording, addSource);
        }

        FriendRequest req = new FriendRequest();
        req.setFromUserId(fromUserId);
        req.setToUserId(toUserId);
        req.setAddWording(trimWording(addWording));
        req.setAddSource(addSource);
        req.setStatus(Status.pending);
        requestRepository.save(req);
        realtimePublisher.pendingCreated(req);
        return new CreateRequestResult("pending", req.getId());
    }

    @Transactional
    public void acceptRequest(String toUserId, long requestId) {
        FriendRequest req = requestRepository.findByIdAndToUserId(requestId, toUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (req.getStatus() != Status.pending) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_ALREADY_HANDLED");
        }
        assertNotBlocked(req.getFromUserId(), req.getToUserId());
        finalizeAccept(req);
    }

    @Transactional
    public void rejectRequest(String toUserId, long requestId) {
        FriendRequest req = requestRepository.findByIdAndToUserId(requestId, toUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (req.getStatus() != Status.pending) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_ALREADY_HANDLED");
        }
        applyReject(req);
    }

    /**
     * 作废 A↔B 双向 pending。必须用接收人 {@code toUserId} 定位申请，禁止传错方向。
     */
    @Transactional
    public void rejectPendingBetween(String userA, String userB) {
        if (userA == null || userB == null || userA.isBlank() || userB.isBlank() || userA.equals(userB)) {
            return;
        }
        rejectPendingIfPresent(userB, userA);
        rejectPendingIfPresent(userA, userB);
    }

    private void rejectPendingIfPresent(String toUserId, String fromUserId) {
        requestRepository.findByFromUserIdAndToUserIdAndStatus(fromUserId, toUserId, Status.pending)
            .ifPresent(this::applyReject);
    }

    private void applyReject(FriendRequest req) {
        if (req.getStatus() != Status.pending) {
            return;
        }
        Instant now = Instant.now();
        req.setStatus(Status.rejected);
        req.setHandledAt(now);
        requestRepository.save(req);
        writeHistoryBoth(req.getFromUserId(), req.getToUserId(), req.getAddWording(), req.getAddSource(),
            req.getCreatedAt(), FriendApplicationHistory.Status.rejected, now);
        rateLimiter.clear(req.getFromUserId(), req.getToUserId());
        realtimePublisher.rejected(req);
    }

    private void assertNotBlocked(String userA, String userB) {
        if (blockService.isEitherBlocked(userA, userB)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER_BLOCKED");
        }
    }

    private CreateRequestResult autoAcceptIncoming(String fromUserId, String toUserId,
                                                   String addWording, AddSource addSource) {
        Instant now = Instant.now();
        FriendRequest req = new FriendRequest();
        req.setFromUserId(fromUserId);
        req.setToUserId(toUserId);
        req.setAddWording(trimWording(addWording));
        req.setAddSource(addSource);
        req.setStatus(Status.accepted);
        req.setHandledAt(now);
        requestRepository.save(req);
        friendService.bindMutualFriends(fromUserId, toUserId);
        writeHistoryBoth(fromUserId, toUserId, req.getAddWording(), addSource, req.getCreatedAt(),
            FriendApplicationHistory.Status.accepted, now);
        rateLimiter.clear(fromUserId, toUserId);
        realtimePublisher.autoAccepted(req, fromUserId, toUserId);
        return new CreateRequestResult("auto_accepted", req.getId());
    }

    private CreateRequestResult autoAcceptPair(String fromUserId, String toUserId, FriendRequest reversePending,
                                               String addWording, AddSource addSource) {
        Instant now = Instant.now();
        reversePending.setStatus(Status.accepted);
        reversePending.setHandledAt(now);
        requestRepository.save(reversePending);

        var forwardPending = requestRepository.findByFromUserIdAndToUserIdAndStatus(
            fromUserId, toUserId, Status.pending);
        forwardPending.ifPresent(fp -> {
            fp.setStatus(Status.accepted);
            fp.setHandledAt(now);
            requestRepository.save(fp);
        });

        friendService.bindMutualFriends(fromUserId, toUserId);
        Instant addTime = reversePending.getCreatedAt();
        writeHistoryBoth(fromUserId, toUserId, coalesceWording(addWording, reversePending.getAddWording()),
            addSource, addTime, FriendApplicationHistory.Status.accepted, now);
        rateLimiter.clear(fromUserId, toUserId);
        rateLimiter.clear(toUserId, fromUserId);
        realtimePublisher.autoAccepted(reversePending, fromUserId, toUserId);
        return new CreateRequestResult("auto_accepted", reversePending.getId());
    }

    private void finalizeAccept(FriendRequest req) {
        Instant now = Instant.now();
        req.setStatus(Status.accepted);
        req.setHandledAt(now);
        requestRepository.save(req);
        friendService.bindMutualFriends(req.getFromUserId(), req.getToUserId());
        writeHistoryBoth(req.getFromUserId(), req.getToUserId(), req.getAddWording(), req.getAddSource(),
            req.getCreatedAt(), FriendApplicationHistory.Status.accepted, now);
        rateLimiter.clear(req.getFromUserId(), req.getToUserId());
        realtimePublisher.accepted(req);
    }

    private void writeHistoryBoth(String applicantId, String accepterId, String wording, AddSource source,
                                Instant addTime, FriendApplicationHistory.Status status, Instant handledAt) {
        User applicant = userRepository.findByUserId(applicantId).orElseThrow();
        User accepter = userRepository.findByUserId(accepterId).orElseThrow();

        saveHistory(accepterId, applicantId, applicant, wording, source, addTime, status, handledAt);
        saveHistory(applicantId, accepterId, accepter, wording, source, addTime, status, handledAt);
    }

    private void saveHistory(String userId, String peerUserId, User peer, String wording, AddSource source,
                           Instant addTime, FriendApplicationHistory.Status status, Instant handledAt) {
        if (historyRepository.findByUserIdAndPeerUserIdAndStatus(userId, peerUserId, status).isPresent()) {
            return;
        }
        FriendApplicationHistory h = new FriendApplicationHistory();
        h.setUserId(userId);
        h.setPeerUserId(peerUserId);
        h.setPeerNickname(peer.getNickname());
        h.setPeerFaceUrl(peer.getAvatarUrl());
        h.setAddWording(wording);
        h.setAddSource(source);
        h.setAddTime(addTime);
        h.setStatus(status);
        h.setHandledAt(handledAt);
        historyRepository.save(h);
    }

    private RequestListResponse toListResponse(List<FriendRequest> rows, boolean incoming) {
        List<String> peerIds = rows.stream()
            .map(r -> incoming ? r.getFromUserId() : r.getToUserId())
            .distinct()
            .toList();
        Map<String, User> usersById = userRepository.findByUserIdIn(peerIds).stream()
            .collect(Collectors.toMap(User::getUserId, Function.identity()));
        List<RequestItem> items = rows.stream()
            .map(r -> toItem(r, incoming, usersById))
            .toList();
        return new RequestListResponse(items);
    }

    private BatchDeleteResponse deleteBatch(List<Long> ids, List<FriendRequest> rows) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (ids.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        for (FriendRequest req : rows) {
            deleteRequest(req);
        }
        return new BatchDeleteResponse(rows.size());
    }

    private void deleteRequest(FriendRequest req) {
        if (req.getStatus() == Status.pending) {
            rateLimiter.clear(req.getFromUserId(), req.getToUserId());
        }
        requestRepository.delete(req);
    }

    private RequestItem toItem(FriendRequest req, boolean incoming, Map<String, User> usersById) {
        String peerUserId = incoming ? req.getFromUserId() : req.getToUserId();
        User peer = usersById.get(peerUserId);
        return new RequestItem(
            req.getId(),
            req.getFromUserId(),
            req.getToUserId(),
            peerUserId,
            peer != null ? peer.getNickname() : null,
            peer != null ? peer.getAvatarUrl() : null,
            req.getAddWording(),
            req.getAddSource().name(),
            req.getStatus().name(),
            req.getCreatedAt(),
            req.getHandledAt());
    }

    private static AddSource parseAddSource(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        try {
            return AddSource.valueOf(raw.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    private static String trimWording(String wording) {
        if (wording == null) {
            return null;
        }
        String t = wording.trim();
        return t.isBlank() ? null : t;
    }

    private static String coalesceWording(String a, String b) {
        String ta = trimWording(a);
        return ta != null ? ta : trimWording(b);
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
