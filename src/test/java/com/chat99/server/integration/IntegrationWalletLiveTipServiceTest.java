package com.chat99.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletLiveTipService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class IntegrationWalletLiveTipServiceTest {

    @Mock WalletLiveTipService liveTipService;

    IntegrationWalletLiveTipService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationWalletLiveTipService(liveTipService);
    }

    @Test
    void mapsNinetyNineCurrencyAndDelegates() {
        when(liveTipService.tip("fan", "anchor", WalletCurrency.PLATFORM, 100L, "123456", "tip_1", 3L))
            .thenReturn(Map.of("tipId", 3L, "ledgerOutId", 10L, "ledgerInId", 11L, "feeAmount", 0L));
        var req = new IntegrationWalletLiveTipController.LiveTipRequest(
            "gl_1", "m1", "anchor", "99", 100L, "123456", "tip_1", "加油", 3L);

        Map<String, Object> out = service.tip("fan", req);

        assertThat(out.get("ledgerOutId")).isEqualTo(10L);
        verify(liveTipService).tip("fan", "anchor", WalletCurrency.PLATFORM, 100L, "123456", "tip_1", 3L);
    }

    @Test
    void rejectsUnknownCurrency() {
        var req = new IntegrationWalletLiveTipController.LiveTipRequest(
            "gl_1", "m1", "anchor", "BTC", 100L, "123456", "tip_1", null, 3L);
        assertThatThrownBy(() -> service.tip("fan", req))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("INVALID_INPUT");
        verifyNoInteractions(liveTipService);
    }
}
