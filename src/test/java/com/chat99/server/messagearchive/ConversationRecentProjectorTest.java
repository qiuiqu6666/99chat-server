package com.chat99.server.messagearchive;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationRecentProjectorTest {

    @Mock ConversationRecentRepository repository;

    ConversationRecentProjector projector;

    @BeforeEach
    void setUp() {
        projector = new ConversationRecentProjector(repository);
    }

    @Test
    void apply_c2c_writesBothSides() {
        ImMessageArchiveEvent event = new ImMessageArchiveEvent(
            "e1", "1", "C2C.CallbackAfterSendMsg", "k1",
            ImMessageArchiveParser.CHAT_TYPE_C2C, "u1", "u2", null,
            null, 1000L, "TIMTextElem", "hi", "[]", "{}", 1000L, null);
        projector.apply(List.of(event));
        verify(repository).upsertC2c(eq("u1"), eq("u2"), eq(1000L), eq("k1"),
            isNull(), eq("u1"), eq("TIMTextElem"), eq("hi"));
        verify(repository).upsertC2c(eq("u2"), eq("u1"), eq(1000L), eq("k1"),
            isNull(), eq("u1"), eq("TIMTextElem"), eq("hi"));
    }

    @Test
    void apply_group_writesSingleRow_noFanOut() {
        ImMessageArchiveEvent event = new ImMessageArchiveEvent(
            "e2", "1", "Group.CallbackAfterSendMsg", "g:9",
            ImMessageArchiveParser.CHAT_TYPE_GROUP, "u1", null, "@TGS#abc",
            9L, 2000L, "TIMTextElem", "ghi", "[]", "{}", 2000L, null);
        projector.apply(List.of(event));
        verify(repository).upsertGroup(eq("@TGS#abc"), eq(2000L), eq("g:9"),
            eq(9L), eq("u1"), eq("TIMTextElem"), eq("ghi"));
        verify(repository, never()).upsertC2c(anyString(), anyString(), anyLong(),
            anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString());
    }

    @Test
    void apply_empty_noop() {
        projector.apply(List.of());
        verifyNoInteractions(repository);
    }
}
