package com.chat99.server.push;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationNotifyService {

    /** 单批免打扰同步上限，防止过大写入拖垮请求。 */
    public static final int MAX_BATCH_SIZE = 200;

    private final UserConversationNotifyRepository repository;

    public ConversationNotifyService(UserConversationNotifyRepository repository) {
        this.repository = repository;
    }

    public boolean isMuted(String userId, String chatType, String peerId) {
        if (userId == null || userId.isBlank() || chatType == null || chatType.isBlank()
            || peerId == null || peerId.isBlank()) {
            return false;
        }
        return repository.findByUserIdAndChatTypeAndPeerId(userId, chatType.trim(), peerId.trim())
            .map(UserConversationNotify::isMuted)
            .orElse(false);
    }

    public Set<String> findMutedUserIdsForGroup(String groupId, Collection<String> memberIds) {
        if (groupId == null || groupId.isBlank() || memberIds == null || memberIds.isEmpty()) {
            return Set.of();
        }
        List<String> ids = memberIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return repository.findMutedUserIds("group", groupId.trim(), ids);
    }

    public Set<String> findMutedUserIdsForC2c(Collection<String> userIds, String peerId) {
        if (peerId == null || peerId.isBlank() || userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return repository.findMutedUserIds("c2c", peerId.trim(), userIds);
    }

    @Transactional
    public void setMuted(String userId, String chatType, String peerId, boolean muted) {
        String type = chatType == null ? "" : chatType.trim();
        String peer = peerId == null ? "" : peerId.trim();
        if (userId == null || userId.isBlank() || type.isBlank() || peer.isBlank()) {
            return;
        }
        UserConversationNotify row = repository.findByUserIdAndChatTypeAndPeerId(userId, type, peer)
            .orElseGet(() -> {
                UserConversationNotify created = new UserConversationNotify();
                created.setUserId(userId);
                created.setChatType(type);
                created.setPeerId(peer);
                return created;
            });
        row.setMuted(muted);
        repository.save(row);
    }

    @Transactional
    public void setMutedBatch(String userId, List<NotifySettingItem> items) {
        if (userId == null || userId.isBlank() || items == null || items.isEmpty()) {
            return;
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }

        // 规范化并按 (chatType, peerId) 去重，后写覆盖先写
        Map<String, NotifySettingItem> normalized = new LinkedHashMap<>();
        for (NotifySettingItem item : items) {
            if (item == null) {
                continue;
            }
            String type = item.chatType() == null ? "" : item.chatType().trim();
            String peer = item.peerId() == null ? "" : item.peerId().trim();
            if (type.isBlank() || peer.isBlank()) {
                continue;
            }
            normalized.put(type + '\0' + peer, new NotifySettingItem(type, peer, item.muted()));
        }
        if (normalized.isEmpty()) {
            return;
        }

        Set<String> chatTypes = new HashSet<>();
        Set<String> peerIds = new HashSet<>();
        for (NotifySettingItem item : normalized.values()) {
            chatTypes.add(item.chatType());
            peerIds.add(item.peerId());
        }
        Map<String, UserConversationNotify> existingByKey = new HashMap<>();
        for (UserConversationNotify row : repository.findByUserIdAndChatTypeInAndPeerIdIn(userId, chatTypes, peerIds)) {
            existingByKey.put(row.getChatType() + '\0' + row.getPeerId(), row);
        }

        List<UserConversationNotify> toSave = new ArrayList<>(normalized.size());
        for (Map.Entry<String, NotifySettingItem> entry : normalized.entrySet()) {
            NotifySettingItem item = entry.getValue();
            UserConversationNotify row = existingByKey.get(entry.getKey());
            if (row == null) {
                row = new UserConversationNotify();
                row.setUserId(userId);
                row.setChatType(item.chatType());
                row.setPeerId(item.peerId());
            }
            row.setMuted(item.muted());
            toSave.add(row);
        }
        repository.saveAll(toSave);
    }

    public List<NotifySettingView> listMuted(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return repository.findByUserIdAndMutedTrue(userId).stream()
            .map(row -> new NotifySettingView(row.getChatType(), row.getPeerId(), true))
            .toList();
    }

    public record NotifySettingItem(String chatType, String peerId, boolean muted) {}

    public record NotifySettingView(String chatType, String peerId, boolean muted) {}
}
