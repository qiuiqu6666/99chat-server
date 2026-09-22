package com.chat99.server.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.messagearchive.ChatMessageTableRouter;
import com.chat99.server.messagearchive.ChatMessageWriteRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class ImExternalMessageRecallServiceTest {

    private ImAdminClient imAdminClient;
    private ChatMessageWriteRepository writeRepository;
    private ChatMessageTableRouter tableRouter;
    private ImExternalMessageRecallService service;

    @BeforeEach
    void setUp() {
        imAdminClient = mock(ImAdminClient.class);
        writeRepository = mock(ChatMessageWriteRepository.class);
        tableRouter = mock(ChatMessageTableRouter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMessageWriteRepository> writeProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMessageTableRouter> routerProvider = mock(ObjectProvider.class);
        when(writeProvider.getIfAvailable()).thenReturn(writeRepository);
        when(routerProvider.getIfAvailable()).thenReturn(tableRouter);
        service = new ImExternalMessageRecallService(imAdminClient, writeProvider, routerProvider);
    }

    @Test
    void recallC2cCallsImAndSyncsArchive() {
        when(tableRouter.physicalTablesAround(any(), eq(2))).thenReturn(List.of("chat_message_202507"));
        when(writeRepository.batchMarkRevoked(anyString(), anyList())).thenReturn(1);

        var result = service.recallC2c("user_a", "user_b", "48374_2837546_1557481126", true);

        verify(imAdminClient).adminRecallC2cMessage("user_a", "user_b", "48374_2837546_1557481126");
        assertEquals("c2c", result.chatType());
        assertEquals(1, result.recalledMsgKeys().size());
        assertEquals(1, result.archiveUpdated());
    }

    @Test
    void recallC2cSkipsArchiveWhenDisabled() {
        var result = service.recallC2c("user_a", "user_b", "48374_2837546_1557481126", false);

        verify(imAdminClient).adminRecallC2cMessage("user_a", "user_b", "48374_2837546_1557481126");
        verify(writeRepository, never()).batchMarkRevoked(anyString(), anyList());
        assertEquals(0, result.archiveUpdated());
    }

    @Test
    void recallGroupParsesPartialSuccess() {
        when(imAdminClient.adminRecallGroupMessages(eq("@TGS#1"), anyList(), any()))
            .thenReturn(Map.of(
                "ErrorCode", 0,
                "RecallRetList", List.of(
                    Map.of("MsgSeq", 100, "RetCode", 0),
                    Map.of("MsgSeq", 101, "RetCode", 10030))));
        when(tableRouter.physicalTablesAround(any(), eq(2))).thenReturn(List.of("chat_message_202507"));
        when(writeRepository.batchMarkRevoked(anyString(), anyList())).thenReturn(1);

        var result = service.recallGroup("@TGS#1", List.of(100L, 101L), "test", true);

        assertEquals(1, result.recalledMsgKeys().size());
        assertEquals("@TGS#1:100", result.recalledMsgKeys().get(0));
        assertEquals(2, result.groupResults().size());
    }

    @Test
    void rejectEmptyC2cInput() {
        assertThrows(ResponseStatusException.class,
            () -> service.recallC2c("", "user_b", "key", false));
    }

    @Test
    void rejectTooManyGroupSeq() {
        assertThrows(ResponseStatusException.class,
            () -> service.recallGroup("@TGS#1", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), null, false));
    }
}
