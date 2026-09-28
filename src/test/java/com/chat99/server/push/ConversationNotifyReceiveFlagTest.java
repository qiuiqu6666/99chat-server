package com.chat99.server.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationNotifyReceiveFlagTest {

    @Mock UserConversationNotifyRepository repository;

    ConversationNotifyService service;

    @BeforeEach
    void setUp() {
        service = new ConversationNotifyService(repository);
        when(repository.findByUserIdAndChatTypeAndPeerId("u1", "group", "g1"))
            .thenReturn(Optional.empty());
    }

    @Test
    void acceptNotNotifyMutesEveryMessage() {
        service.applyGroupReceiveFlag("u1", "g1", "AcceptNotNotify");
        UserConversationNotify saved = saved();
        assertTrue(saved.isMuted());
        assertEquals(ConversationNotifyService.OPT_ACCEPT_NOT_NOTIFY, saved.getReceiveOpt());
        assertTrue(ConversationNotifyService.suppressGroupPush(true, false, true));
    }

    @Test
    void acceptAndNotifyClearsMute() {
        service.applyGroupReceiveFlag("u1", "g1", "AcceptAndNotify");
        UserConversationNotify saved = saved();
        assertFalse(saved.isMuted());
        assertEquals(ConversationNotifyService.OPT_ACCEPT_AND_NOTIFY, saved.getReceiveOpt());
        assertFalse(ConversationNotifyService.suppressGroupPush(false, false, false));
    }

    @Test
    void exceptAtStillPushesMentions() {
        service.applyGroupReceiveFlag("u1", "g1", "AcceptNotNotifyExceptAt");
        UserConversationNotify saved = saved();
        assertFalse(saved.isMuted());
        assertEquals(ConversationNotifyService.OPT_EXCEPT_AT, saved.getReceiveOpt());
        assertTrue(ConversationNotifyService.suppressGroupPush(false, true, false));
        assertFalse(ConversationNotifyService.suppressGroupPush(false, true, true));
    }

    private UserConversationNotify saved() {
        ArgumentCaptor<UserConversationNotify> saved = ArgumentCaptor.forClass(UserConversationNotify.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }
}
