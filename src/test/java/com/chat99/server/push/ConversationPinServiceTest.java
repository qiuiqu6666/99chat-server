package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.ConversationPinRealtimePublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ConversationPinServiceTest {

    private static final String USER = "user001";
    private static final String PEER = "user002";
    private static final String GROUP = "@TGS#2ABCDEF";

    @Mock UserConversationPinRepository repository;
    @Mock ConversationPinRealtimePublisher realtime;

    ConversationPinService service;

    @BeforeEach
    void setup() {
        service = new ConversationPinService(repository, realtime);
    }

    @Test
    void listEmpty_returnsEmptyItemsAndZeroUpdatedAt() {
        when(repository.findByUserIdOrderByPinnedAtDesc(USER)).thenReturn(List.of());

        ConversationPinService.PinListResponse resp = service.list(USER);

        assertThat(resp.items()).isEmpty();
        assertThat(resp.updatedAt()).isZero();
        assertThat(resp.serverTime()).isPositive();
    }

    @Test
    void pin_upsertsAndReturnsFullItems() {
        when(repository.existsById(new UserConversationPinId(USER, "c2c", PEER))).thenReturn(false);
        when(repository.countByUserId(USER)).thenReturn(0L);
        when(repository.findById(new UserConversationPinId(USER, "c2c", PEER))).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByUserIdOrderByPinnedAtDesc(USER)).thenAnswer(inv -> {
            UserConversationPin row = new UserConversationPin();
            row.setUserId(USER);
            row.setChatType("c2c");
            row.setPeerId(PEER);
            row.setPinnedAt(1L);
            row.setUpdatedAt(1L);
            return List.of(row);
        });

        ConversationPinService.PinMutationResponse resp = service.setPinned(USER, "C2C", PEER, true);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.pinned()).isTrue();
        assertThat(resp.pinnedAt()).isNotNull();
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).peerId()).isEqualTo(PEER);
        verify(realtime).singleChanged(eq(USER), eq("c2c"), eq(PEER), eq(true),
            any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unpin_deletesEvenIfMissing_andReturnsEmptyItems() {
        when(repository.findByUserIdOrderByPinnedAtDesc(USER)).thenReturn(List.of());

        ConversationPinService.PinMutationResponse resp = service.setPinned(USER, "group", GROUP, false);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.pinned()).isFalse();
        assertThat(resp.pinnedAt()).isNull();
        assertThat(resp.items()).isEmpty();
        verify(repository).deleteById(new UserConversationPinId(USER, "group", GROUP));
        verify(realtime).singleChanged(eq(USER), eq("group"), eq(GROUP), eq(false),
            any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void pin101st_newRow_rejected() {
        when(repository.existsById(new UserConversationPinId(USER, "c2c", PEER))).thenReturn(false);
        when(repository.countByUserId(USER)).thenReturn((long) ConversationPinService.MAX_PINS);

        assertThatThrownBy(() -> service.setPinned(USER, "c2c", PEER, true))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("PIN_LIMIT_EXCEEDED");
        verify(repository, never()).save(any());
    }

    @Test
    void pinExistingWhenAtLimit_allowed() {
        UserConversationPin existing = new UserConversationPin();
        existing.setUserId(USER);
        existing.setChatType("c2c");
        existing.setPeerId(PEER);
        existing.setPinnedAt(100L);
        existing.setUpdatedAt(100L);
        when(repository.existsById(new UserConversationPinId(USER, "c2c", PEER))).thenReturn(true);
        when(repository.findById(new UserConversationPinId(USER, "c2c", PEER))).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByUserIdOrderByPinnedAtDesc(USER)).thenReturn(List.of(existing));

        ConversationPinService.PinMutationResponse resp = service.setPinned(USER, "c2c", PEER, true);

        assertThat(resp.ok()).isTrue();
        verify(repository).save(existing);
    }

    @Test
    void invalidChatType_rejected() {
        assertThatThrownBy(() -> service.setPinned(USER, "invalid", PEER, true))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("INVALID_INPUT");
    }

    @Test
    void batch_unpinsThenPins_publishesBatchWithItems() {
        when(repository.findByUserIdOrderByPinnedAtDesc(USER)).thenReturn(new ArrayList<>());
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.countByUserId(USER)).thenReturn(1L);

        ConversationPinService.PinBatchResponse resp = service.setPinnedBatch(USER, List.of(
            new ConversationPinService.PinSettingItem("c2c", PEER, false),
            new ConversationPinService.PinSettingItem("group", GROUP, true)));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.count()).isEqualTo(2);
        verify(repository).deleteById(new UserConversationPinId(USER, "c2c", PEER));
        ArgumentCaptor<UserConversationPin> saveCaptor = ArgumentCaptor.forClass(UserConversationPin.class);
        verify(repository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getPeerId()).isEqualTo(GROUP);
        verify(realtime).batchChanged(eq(USER), any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
