package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationArchiveRealtimeNotifierTest {

    @Mock RealtimeEventPublisher publisher;

    ConversationArchiveRealtimeNotifier notifier;

    @BeforeEach
    void setup() {
        notifier = new ConversationArchiveRealtimeNotifier(publisher);
    }

    @Test
    void singleChange_forwardsPayload() {
        notifier.onCommitted(new ConversationArchiveRealtimePublisher.ConversationArchiveChangedCommittedEvent(
            "user001", "group", "@TGS#abc", true, 1718592100000L, 1718592100000L, false));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).sendToUser(eq("user001"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("event")).isEqualTo("conversation_archive_changed");
        assertThat(payload.get("chatType")).isEqualTo("group");
        assertThat(payload.get("peerId")).isEqualTo("@TGS#abc");
        assertThat(payload.get("archived")).isEqualTo(true);
        assertThat(payload.get("archivedAt")).isEqualTo(1718592100000L);
        assertThat(payload.get("updatedAt")).isEqualTo(1718592100000L);
        assertThat(payload).doesNotContainKey("batch");
    }

    @Test
    void batchChange_forwardsBatchFlag() {
        notifier.onCommitted(new ConversationArchiveRealtimePublisher.ConversationArchiveChangedCommittedEvent(
            "user001", null, null, false, null, 1718592200000L, true));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).sendToUser(eq("user001"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("event")).isEqualTo("conversation_archive_changed");
        assertThat(payload.get("batch")).isEqualTo(true);
        assertThat(payload.get("updatedAt")).isEqualTo(1718592200000L);
        assertThat(payload).doesNotContainKey("chatType");
    }
}
