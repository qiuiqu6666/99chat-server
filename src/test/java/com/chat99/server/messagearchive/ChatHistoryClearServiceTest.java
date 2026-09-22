package com.chat99.server.messagearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatHistoryClearServiceTest {

    @Mock ChatHistoryClearRepository repository;

    private ChatHistoryClearService service;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryClearService(repository);
    }

    @Test
    void clearC2c_recordsWatermark() {
        var result = service.clearC2c("user_a", "user_b");

        verify(repository).markCleared(
            eq("user_a"),
            eq(ChatHistoryClearRepository.CHAT_TYPE_C2C),
            eq("user_b"),
            eq(result.clearedBeforeMs()));
        assertEquals(result.clearedBeforeMs(), result.clearedBeforeMs());
    }

    @Test
    void clearedBeforeMsGroup_delegatesToRepository() {
        when(repository.getClearedBeforeMs("user_a", ChatHistoryClearRepository.CHAT_TYPE_GROUP, "g1"))
            .thenReturn(1718450000000L);

        assertEquals(1718450000000L, service.clearedBeforeMsGroup("user_a", "g1"));
    }
}
