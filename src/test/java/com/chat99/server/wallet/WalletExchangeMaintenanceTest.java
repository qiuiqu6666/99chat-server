package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.notify.PlatformWalletNoticeService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WalletExchangeMaintenanceTest {

    @Mock ExchangeRateService rateService;
    @Mock WalletLedgerService ledgerService;
    @Mock WalletExchangeOrderRepository orderRepository;
    @Mock WalletExchangeConfigRepository exchangeConfigRepository;
    @Mock WalletPlatformStatsRepository statsRepository;
    @Mock PayPinService payPinService;
    @Mock WalletLimitService limitService;
    @Mock WalletFeeService feeService;
    @Mock PlatformWalletNoticeService platformWalletNotice;

    WalletExchangeService service;

    @BeforeEach
    void setup() {
        service = new WalletExchangeService(
            rateService, ledgerService, orderRepository, exchangeConfigRepository,
            statsRepository, payPinService, limitService, feeService, platformWalletNotice);
    }

    @Test
    void exchange_rejectsWhenDisabled() {
        WalletExchangeConfig cfg = new WalletExchangeConfig();
        cfg.setId(1L);
        cfg.setEnabled(false);
        when(exchangeConfigRepository.findById(1L)).thenReturn(Optional.of(cfg));

        assertThatThrownBy(() -> service.exchange(
            "u1", ExchangeDirection.USDT_TO_PLATFORM, 1_000_000L, "123456"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                ResponseStatusException rse = (ResponseStatusException) ex;
                org.assertj.core.api.Assertions.assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                org.assertj.core.api.Assertions.assertThat(rse.getReason()).isEqualTo("EXCHANGE_MAINTENANCE");
            });

        verify(payPinService, never()).requireSetAndVerify(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
        verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
