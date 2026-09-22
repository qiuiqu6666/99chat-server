package com.chat99.server.push;

import com.chat99.server.realtime.ConversationArchiveRealtimePublisher;
import com.chat99.server.realtime.ConversationFolderRealtimePublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationArchiveService {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_BATCH_SIZE = 100;

    private final UserConversationArchiveRepository repository;
    private final UserConversationFolderMemberRepository folderMemberRepository;
    private final ConversationArchiveRealtimePublisher realtime;
    private final ConversationFolderRealtimePublisher folderRealtime;

    public ConversationArchiveService(UserConversationArchiveRepository repository,
                                      UserConversationFolderMemberRepository folderMemberRepository,
                                      ConversationArchiveRealtimePublisher realtime,
                                      ConversationFolderRealtimePublisher folderRealtime) {
        this.repository = repository;
        this.folderMemberRepository = folderMemberRepository;
        this.realtime = realtime;
        this.folderRealtime = folderRealtime;
    }

    public record ArchiveItemView(String chatType, String peerId, long archivedAt, long updatedAt) {}

    public record ArchiveListResponse(
        List<ArchiveItemView> items,
        long serverTime,
        Long nextSince,
        boolean hasMore) {}

    public record ArchiveMutationResponse(
        boolean ok,
        String chatType,
        String peerId,
        boolean archived,
        Long archivedAt,
        long updatedAt) {}

    public record ArchiveBatchResponse(boolean ok, int count, long updatedAt) {}

    public record ArchiveSettingItem(String chatType, String peerId, boolean archived) {}

    public ArchiveListResponse list(String userId, Long since, Integer limit) {
        requireUserId(userId);
        int pageSize = normalizeLimit(limit);
        long serverTime = System.currentTimeMillis();
        List<UserConversationArchive> rows = since == null || since <= 0
            ? repository.findByUserIdOrderByUpdatedAtAsc(userId)
            : repository.findByUserIdAndUpdatedAtGreaterThanOrderByUpdatedAtAsc(userId, since);
        boolean hasMore = rows.size() > pageSize;
        List<UserConversationArchive> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<ArchiveItemView> items = page.stream()
            .map(this::toView)
            .toList();
        Long nextSince = items.isEmpty()
            ? (since == null ? serverTime : since)
            : items.stream().mapToLong(ArchiveItemView::updatedAt).max().orElse(serverTime);
        return new ArchiveListResponse(items, serverTime, nextSince, hasMore);
    }

    @Transactional
    public ArchiveMutationResponse setArchived(String userId, String chatType, String peerId, boolean archived) {
        requireUserId(userId);
        NormalizedConversation conv = normalizeConversation(userId, chatType, peerId);
        long nowMs = System.currentTimeMillis();
        if (archived) {
            UserConversationArchive row = repository
                .findById(new UserConversationArchiveId(userId, conv.chatType(), conv.peerId()))
                .orElseGet(() -> {
                    UserConversationArchive created = new UserConversationArchive();
                    created.setUserId(userId);
                    created.setChatType(conv.chatType());
                    created.setPeerId(conv.peerId());
                    return created;
                });
            row.setArchivedAt(nowMs);
            row.setUpdatedAt(nowMs);
            repository.save(row);
            clearFolderMemberships(userId, conv.chatType(), conv.peerId(), nowMs);
            realtime.singleChanged(userId, conv.chatType(), conv.peerId(), true, nowMs, nowMs);
            return new ArchiveMutationResponse(true, conv.chatType(), conv.peerId(), true, nowMs, nowMs);
        }
        repository.deleteById(new UserConversationArchiveId(userId, conv.chatType(), conv.peerId()));
        realtime.singleChanged(userId, conv.chatType(), conv.peerId(), false, null, nowMs);
        return new ArchiveMutationResponse(true, conv.chatType(), conv.peerId(), false, null, nowMs);
    }

    @Transactional
    public ArchiveBatchResponse setArchivedBatch(String userId, List<ArchiveSettingItem> items) {
        requireUserId(userId);
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<NormalizedConversation> normalized = new ArrayList<>(items.size());
        for (ArchiveSettingItem item : items) {
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            normalized.add(normalizeConversation(userId, item.chatType(), item.peerId()));
        }
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            ArchiveSettingItem item = items.get(i);
            NormalizedConversation conv = normalized.get(i);
            if (item.archived()) {
                UserConversationArchive row = repository
                    .findById(new UserConversationArchiveId(userId, conv.chatType(), conv.peerId()))
                    .orElseGet(() -> {
                        UserConversationArchive created = new UserConversationArchive();
                        created.setUserId(userId);
                        created.setChatType(conv.chatType());
                        created.setPeerId(conv.peerId());
                        return created;
                    });
                row.setArchivedAt(nowMs);
                row.setUpdatedAt(nowMs);
                repository.save(row);
                clearFolderMemberships(userId, conv.chatType(), conv.peerId(), nowMs);
            } else {
                repository.deleteById(new UserConversationArchiveId(userId, conv.chatType(), conv.peerId()));
            }
        }
        realtime.batchChanged(userId, nowMs);
        return new ArchiveBatchResponse(true, items.size(), nowMs);
    }

    /** 归档与分组互斥：入归档时清除所有分组成员关系。 */
    private void clearFolderMemberships(String userId, String chatType, String peerId, long nowMs) {
        long existing = folderMemberRepository.countByUserIdAndChatTypeAndPeerId(userId, chatType, peerId);
        if (existing <= 0) {
            return;
        }
        folderMemberRepository.deleteByUserIdAndChatTypeAndPeerId(userId, chatType, peerId);
        folderRealtime.batchChanged(userId, nowMs);
    }

    private ArchiveItemView toView(UserConversationArchive row) {
        return new ArchiveItemView(row.getChatType(), row.getPeerId(), row.getArchivedAt(), row.getUpdatedAt());
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static NormalizedConversation normalizeConversation(String userId, String chatType, String peerId) {
        String type = chatType == null ? "" : chatType.trim().toLowerCase(Locale.ROOT);
        String peer = peerId == null ? "" : peerId.trim();
        if (!"c2c".equals(type) && !"group".equals(type) || peer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if ("c2c".equals(type) && peer.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_ARCHIVE_SELF");
        }
        return new NormalizedConversation(type, peer);
    }

    private record NormalizedConversation(String chatType, String peerId) {}
}
