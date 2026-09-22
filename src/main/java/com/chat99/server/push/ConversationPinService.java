package com.chat99.server.push;

import com.chat99.server.realtime.ConversationPinRealtimePublisher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationPinService {

    public static final int MAX_PINS = 100;
    private static final int MAX_BATCH_SIZE = 100;

    private final UserConversationPinRepository repository;
    private final ConversationPinRealtimePublisher realtime;

    public ConversationPinService(UserConversationPinRepository repository,
                                  ConversationPinRealtimePublisher realtime) {
        this.repository = repository;
        this.realtime = realtime;
    }

    public record PinItemView(String chatType, String peerId, long pinnedAt, long updatedAt) {}

    public record PinListResponse(
        List<PinItemView> items,
        long serverTime,
        long updatedAt) {}

    public record PinMutationResponse(
        boolean ok,
        String chatType,
        String peerId,
        boolean pinned,
        Long pinnedAt,
        long updatedAt,
        List<PinItemView> items,
        long serverTime) {}

    public record PinBatchResponse(
        boolean ok,
        int count,
        long updatedAt,
        List<PinItemView> items,
        long serverTime) {}

    public record PinSettingItem(String chatType, String peerId, boolean pinned) {}

    public PinListResponse list(String userId) {
        requireUserId(userId);
        long serverTime = System.currentTimeMillis();
        List<PinItemView> items = loadItems(userId);
        return new PinListResponse(items, serverTime, maxUpdatedAt(items, serverTime));
    }

    @Transactional
    public PinMutationResponse setPinned(String userId, String chatType, String peerId, boolean pinned) {
        requireUserId(userId);
        NormalizedConversation conv = normalizeConversation(chatType, peerId);
        long nowMs = System.currentTimeMillis();
        UserConversationPinId id = new UserConversationPinId(userId, conv.chatType(), conv.peerId());
        if (pinned) {
            boolean exists = repository.existsById(id);
            if (!exists && repository.countByUserId(userId) >= MAX_PINS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PIN_LIMIT_EXCEEDED");
            }
            UserConversationPin row = repository.findById(id).orElseGet(() -> {
                UserConversationPin created = new UserConversationPin();
                created.setUserId(userId);
                created.setChatType(conv.chatType());
                created.setPeerId(conv.peerId());
                return created;
            });
            row.setPinnedAt(nowMs);
            row.setUpdatedAt(nowMs);
            repository.save(row);
        } else {
            repository.deleteById(id);
        }
        List<PinItemView> items = loadItems(userId);
        long updatedAt = maxUpdatedAt(items, nowMs);
        realtime.singleChanged(userId, conv.chatType(), conv.peerId(), pinned, items, updatedAt);
        return new PinMutationResponse(
            true, conv.chatType(), conv.peerId(), pinned,
            pinned ? nowMs : null, updatedAt, items, System.currentTimeMillis());
    }

    @Transactional
    public PinBatchResponse setPinnedBatch(String userId, List<PinSettingItem> items) {
        requireUserId(userId);
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<PinSettingItem> normalizedOps = new ArrayList<>(items.size());
        for (PinSettingItem item : items) {
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            NormalizedConversation conv = normalizeConversation(item.chatType(), item.peerId());
            normalizedOps.add(new PinSettingItem(conv.chatType(), conv.peerId(), item.pinned()));
        }

        long nowMs = System.currentTimeMillis();
        // 先取消再置顶，避免中间态误触上限
        for (PinSettingItem op : normalizedOps) {
            if (!op.pinned()) {
                repository.deleteById(new UserConversationPinId(userId, op.chatType(), op.peerId()));
            }
        }
        Set<UserConversationPinId> alreadyPinned = new HashSet<>();
        for (UserConversationPin row : repository.findByUserIdOrderByPinnedAtDesc(userId)) {
            alreadyPinned.add(new UserConversationPinId(row.getUserId(), row.getChatType(), row.getPeerId()));
        }
        for (PinSettingItem op : normalizedOps) {
            if (!op.pinned()) {
                continue;
            }
            UserConversationPinId id = new UserConversationPinId(userId, op.chatType(), op.peerId());
            if (!alreadyPinned.contains(id) && alreadyPinned.size() >= MAX_PINS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PIN_LIMIT_EXCEEDED");
            }
            UserConversationPin row = repository.findById(id).orElseGet(() -> {
                UserConversationPin created = new UserConversationPin();
                created.setUserId(userId);
                created.setChatType(op.chatType());
                created.setPeerId(op.peerId());
                return created;
            });
            row.setPinnedAt(nowMs);
            row.setUpdatedAt(nowMs);
            repository.save(row);
            alreadyPinned.add(id);
        }
        if (repository.countByUserId(userId) > MAX_PINS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PIN_LIMIT_EXCEEDED");
        }

        List<PinItemView> allItems = loadItems(userId);
        long updatedAt = maxUpdatedAt(allItems, nowMs);
        realtime.batchChanged(userId, allItems, updatedAt);
        return new PinBatchResponse(true, normalizedOps.size(), updatedAt, allItems, System.currentTimeMillis());
    }

    private List<PinItemView> loadItems(String userId) {
        return repository.findByUserIdOrderByPinnedAtDesc(userId).stream()
            .map(this::toView)
            .toList();
    }

    private PinItemView toView(UserConversationPin row) {
        return new PinItemView(row.getChatType(), row.getPeerId(), row.getPinnedAt(), row.getUpdatedAt());
    }

    private static long maxUpdatedAt(List<PinItemView> items, long fallback) {
        if (items == null || items.isEmpty()) {
            return 0L;
        }
        return items.stream().mapToLong(PinItemView::updatedAt).max().orElse(fallback);
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private static NormalizedConversation normalizeConversation(String chatType, String peerId) {
        String type = chatType == null ? "" : chatType.trim().toLowerCase(Locale.ROOT);
        String peer = peerId == null ? "" : peerId.trim();
        if (!"c2c".equals(type) && !"group".equals(type) || peer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return new NormalizedConversation(type, peer);
    }

    private record NormalizedConversation(String chatType, String peerId) {}
}
