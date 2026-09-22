package com.chat99.server.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class SyncDomainControllerTest {
    @Test
    void completedPageRevisionIsForwardedWhenCursorIsEmpty() {
        var provider = mock(SyncProvider.class);
        when(provider.domain()).thenReturn(SyncDomain.CONTACTS);
        var controller = new SyncDomainController(List.of(provider));
        var auth = new UsernamePasswordAuthenticationToken("owner", null);

        controller.changes(auth, "contacts", "", 200, null, 42L);

        var cursor = ArgumentCaptor.forClass(String.class);
        verify(provider).changes(eq("owner"), cursor.capture(), eq(200), eq(Map.of()));
        assertThat(OpaqueCursor.decode(cursor.getValue()).revision()).isEqualTo(42L);
        assertThat(OpaqueCursor.decode(cursor.getValue()).domain()).isEqualTo("contacts");
    }

    @Test
    void paginationCursorTakesPrecedenceOverOriginalRevision() {
        var provider = mock(SyncProvider.class);
        when(provider.domain()).thenReturn(SyncDomain.CONTACTS);
        var controller = new SyncDomainController(List.of(provider));
        var auth = new UsernamePasswordAuthenticationToken("owner", null);
        var cursor = OpaqueCursor.encode("contacts", 50L, 50L);

        controller.changes(auth, "contacts", cursor, 200, null, 42L);

        verify(provider).changes("owner", cursor, 200, Map.of());
    }

    @Test
    void negativeRevisionIsRejected() {
        var provider = mock(SyncProvider.class);
        when(provider.domain()).thenReturn(SyncDomain.CONTACTS);
        var controller = new SyncDomainController(List.of(provider));
        var auth = new UsernamePasswordAuthenticationToken("owner", null);
        assertThatThrownBy(() -> controller.changes(auth, "contacts", "", 200, null, -1L))
            .isInstanceOf(ResponseStatusException.class);
        verify(provider, never()).changes(anyString(), any(), anyInt(), anyMap());
    }
}
