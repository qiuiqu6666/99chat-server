package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.chat99.server.push.ConversationPinService.PinItemView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationPinRealtimeNotifierTest {

    @Mock RealtimeEventPublisher publisher;

    ConversationPinRealtimeNotifier notifier;

    @BeforeEach
    void setup() {
        notifier = new ConversationPinRealtimeNotifier(publisher);
    }

    @Test
    void singleChange_includesFullItems() {
        List<PinItemView> items = List.of(
            new PinItemView("c2c", "user123", 1718592000000L, 1718592000000L));
        notifier.onCommitted(new ConversationPinRealtimePublisher.ConversationPinChangedCommittedEvent(
            "user001", items, 1718592000000L, false, "c2c", "user123", true));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).sendToUser(eq("user001"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("event")).isEqualTo("conversation_pin_changed");
        assertThat(payload.get("updatedAt")).isEqualTo(1718592000000L);
        assertThat(payload.get("pinned")).isEqualTo(true);
        assertThat(payload.get("chatType")).isEqualTo("c2c");
        assertThat(payload.get("peerId")).isEqualTo("user123");
        assertThat(payload).doesNotContainKey("batch");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outItems = (List<Map<String, Object>>) payload.get("items");
        assertThat(outItems).hasSize(1);
        assertThat(outItems.get(0).get("peerId")).isEqualTo("user123");
        assertThat(outItems.get(0).get("pinnedAt")).isEqualTo(1718592000000L);
    }

    @Test
    void batchChange_includesBatchFlagAndItems() {
        notifier.onCommitted(new ConversationPinRealtimePublisher.ConversationPinChangedCommittedEvent(
            "user001", List.of(), 1718592200000L, true, null, null, null));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).sendToUser(eq("user001"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("event")).isEqualTo("conversation_pin_changed");
        assertThat(payload.get("batch")).isEqualTo(true);
        assertThat(payload.get("items")).isEqualTo(List.of());
        assertThat(payload.get("updatedAt")).isEqualTo(1718592200000L);
        assertThat(payload).doesNotContainKey("chatType");
    }
}
