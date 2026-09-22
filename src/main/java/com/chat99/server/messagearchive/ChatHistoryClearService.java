package com.chat99.server.messagearchive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ChatHistoryClearService {

    public record ClearResult(long clearedBeforeMs) {}

    private final ChatHistoryClearRepository repository;

    public ChatHistoryClearService(ChatHistoryClearRepository repository) {
        this.repository = repository;
    }

    public long clearedBeforeMsC2c(String userId, String peerUserId) {
        return repository.getClearedBeforeMs(userId, ChatHistoryClearRepository.CHAT_TYPE_C2C, peerUserId);
    }

    public long clearedBeforeMsGroup(String userId, String groupId) {
        return repository.getClearedBeforeMs(userId, ChatHistoryClearRepository.CHAT_TYPE_GROUP, groupId);
    }

    public ClearResult clearC2c(String userId, String peerUserId) {
        long clearedBeforeMs = System.currentTimeMillis();
        repository.markCleared(userId, ChatHistoryClearRepository.CHAT_TYPE_C2C, peerUserId, clearedBeforeMs);
        return new ClearResult(clearedBeforeMs);
    }

    public ClearResult clearGroup(String userId, String groupId) {
        long clearedBeforeMs = System.currentTimeMillis();
        repository.markCleared(userId, ChatHistoryClearRepository.CHAT_TYPE_GROUP, groupId, clearedBeforeMs);
        return new ClearResult(clearedBeforeMs);
    }
}
