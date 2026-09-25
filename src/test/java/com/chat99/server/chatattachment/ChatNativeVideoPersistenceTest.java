package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatNativeVideoPersistenceTest {
    @Mock ChatNativeVideoMessageRepository messages;
    @Mock ChatAttachmentReferenceRepository references;
    private ChatNativeVideoPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new ChatNativeVideoPersistence(messages, references);
    }

    @Test
    void pendingOperationGrantsOneDurableDispatchIntent() {
        ChatNativeVideoMessage row = new ChatNativeVideoMessage();
        row.setOperationId("op");
        row.setStatus(ChatNativeVideoStatus.pending);
        when(messages.findByIdForUpdate("op")).thenReturn(Optional.of(row));

        assertThat(persistence.beginDispatch("op")).isTrue();
        assertThat(row.getStatus()).isEqualTo(ChatNativeVideoStatus.unknown);
        assertThat(persistence.beginDispatch("op")).isFalse();
        verify(messages).save(row);
    }

    @Test
    void lateProviderSuccessDoesNotReviveARevokedReference() {
        ChatNativeVideoMessage row = new ChatNativeVideoMessage();
        row.setOperationId("op");
        row.setReferenceId("ref");
        row.setStatus(ChatNativeVideoStatus.unknown);
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setReferenceId("ref");
        ref.setState(ChatReferenceState.revoked);
        when(messages.findByIdForUpdate("op")).thenReturn(Optional.of(row));
        when(references.findByReferenceId("ref")).thenReturn(Optional.of(ref));

        persistence.markSent("op", "mk", null);

        assertThat(row.getStatus()).isEqualTo(ChatNativeVideoStatus.sent);
        assertThat(ref.getState()).isEqualTo(ChatReferenceState.revoked);
        verify(references, never()).save(any());
    }
}
