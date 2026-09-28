package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class WalletLegacyCardEndpointTest {
    @Test void oldClientsAreRejectedBeforeAnyDebit() {
        var controller = mock(WalletController.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> controller.transfer(null, null)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(426));
        assertThatThrownBy(() -> controller.sendRedPacket(null, null)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getReason()).isEqualTo("WALLET_SERVER_CARD_REQUIRED"));
    }
}
