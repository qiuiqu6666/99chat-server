package com.chat99.server.user;

import com.chat99.server.user.FriendApplicationHistory.AddSource;
import com.chat99.server.user.FriendApplicationHistory.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FriendApplicationService {

    public record HistoryItem(
        Long id,
        String peerUserId,
        String peerNickname,
        String peerFaceUrl,
        String addWording,
        String addSource,
        Instant addTime,
        String status,
        Instant handledAt) {}

    public record CursorPage(
        List<HistoryItem> content,
        Instant nextCursor,
        boolean hasMore) {}

    public record AcceptRequest(
        String peerUserId,
        Instant addTime,
        String addWording,
        String addSource) {}

    private final FriendApplicationRepository repository;
    private final UserRepository userRepository;

    public FriendApplicationService(FriendApplicationRepository repository,
                                    UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    public CursorPage history(String userId, Instant cursor, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<FriendApplicationHistory> rows = repository.findByUserIdBeforeCursor(
            userId, cursor, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }
        Instant nextCursor = rows.isEmpty() ? null : rows.get(rows.size() - 1).getAddTime();
        List<HistoryItem> items = rows.stream().map(this::toItem).toList();
        return new CursorPage(items, nextCursor, hasMore);
    }

    @Transactional
    public void acceptApplication(String authUserId, AcceptRequest req) {
        User applicant = userRepository.findByUserId(req.peerUserId())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        User accepter = userRepository.findByUserId(authUserId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        Instant now = Instant.now();

        FriendApplicationHistory h1 = new FriendApplicationHistory();
        h1.setUserId(authUserId);
        h1.setPeerUserId(req.peerUserId());
        h1.setPeerNickname(applicant.getNickname());
        h1.setPeerFaceUrl(applicant.getAvatarUrl());
        h1.setAddWording(req.addWording());
        h1.setAddSource(AddSource.valueOf(req.addSource()));
        h1.setAddTime(req.addTime());
        h1.setStatus(Status.accepted);
        h1.setHandledAt(now);
        repository.save(h1);

        FriendApplicationHistory h2 = new FriendApplicationHistory();
        h2.setUserId(req.peerUserId());
        h2.setPeerUserId(authUserId);
        h2.setPeerNickname(accepter.getNickname());
        h2.setPeerFaceUrl(accepter.getAvatarUrl());
        h2.setAddWording(req.addWording());
        h2.setAddSource(AddSource.valueOf(req.addSource()));
        h2.setAddTime(req.addTime());
        h2.setStatus(Status.accepted);
        h2.setHandledAt(now);
        repository.save(h2);
    }

    @Transactional
    public Map<String, Object> deleteHistory(String userId, long id) {
        // 软删：写 deleted=1 + deleted_at + item_version（保留行作 tombstone）
        int updated = repository.softDelete(userId, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND");
        }
        return Map.of("ok", true, "id", id);
    }

    private HistoryItem toItem(FriendApplicationHistory h) {
        return new HistoryItem(
            h.getId(),
            h.getPeerUserId(),
            h.getPeerNickname(),
            h.getPeerFaceUrl(),
            h.getAddWording(),
            h.getAddSource().name(),
            h.getAddTime(),
            h.getStatus().name(),
            h.getHandledAt());
    }
}
