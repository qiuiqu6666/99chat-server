package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WalletLiveTipServiceTest {

    @Mock UserRepository userRepository;
    @Mock WalletLedgerRepository ledgerRepository;
    @Mock WalletLedgerService ledgerService;
    @Mock PayPinService payPinService;
    @Mock WalletLimitService limitService;
    @Mock WalletFeeService feeService;
    @Mock WalletPlatformStatsRepository statsRepository;

    WalletLiveTipService service;

    @BeforeEach
    void setUp() {
        service = new WalletLiveTipService(
            userRepository, ledgerRepository, ledgerService,
            payPinService, limitService, feeService, statsRepository);
    }

    @Test
    void rejectsSelfTip() {
        assertThatThrownBy(() -> service.tip(
            "u1", "u1", WalletCurrency.USDT, 1_000_000L, "123456", "tip_1", 9L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("CANNOT_TIP_SELF");
        verify(ledgerService, never()).debit(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void replaysSameClientOrderId() {
        when(userRepository.existsByUserId("anchor")).thenReturn(true);
        WalletLedger out = new WalletLedger();
        out.setId(10L);
        out.setCurrency(WalletCurrency.USDT);
        out.setAmount(-1_000_000L);
        out.setCounterpartUserId("anchor");
        when(ledgerRepository.findFirstByUserIdAndLedgerTypeAndRemarkOrderByIdAsc(
            "fan", WalletLedgerType.LIVE_TIP_OUT, "tip_1")).thenReturn(Optional.of(out));
        WalletLedger in = new WalletLedger();
        in.setId(11L);
        in.setLedgerType(WalletLedgerType.LIVE_TIP_IN);
        when(ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc("LIVE_TIP", 10L))
            .thenReturn(List.of(in));
        when(ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc("LIVE_TIP", 9L))
            .thenReturn(List.of());

        Map<String, Object> result = service.tip(
            "fan", "anchor", WalletCurrency.USDT, 1_000_000L, "123456", "tip_1", 9L);

        assertThat(result.get("ledgerOutId")).isEqualTo(10L);
        assertThat(result.get("ledgerInId")).isEqualTo(11L);
        assertThat(result.get("feeAmount")).isEqualTo(0L);
        verify(payPinService, never()).requireSetAndVerify(any(), any());
        verify(ledgerService, never()).debit(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void writesOutInLedgers() {
        when(userRepository.existsByUserId("anchor")).thenReturn(true);
        when(ledgerRepository.findFirstByUserIdAndLedgerTypeAndRemarkOrderByIdAsc(
            "fan", WalletLedgerType.LIVE_TIP_OUT, "tip_2"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(outLedger(20L)));
        when(feeService.calculateFee(WalletFeeScene.LIVE_TIP, WalletCurrency.USDT, 1_500_000L))
            .thenReturn(0L);
        WalletLedger in = new WalletLedger();
        in.setId(21L);
        in.setLedgerType(WalletLedgerType.LIVE_TIP_IN);
        when(ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc("LIVE_TIP", 20L))
            .thenReturn(List.of(in));

        Map<String, Object> result = service.tip(
            "fan", "anchor", WalletCurrency.USDT, 1_500_000L, "123456", "tip_2", 8L);

        assertThat(result.get("tipId")).isEqualTo(8L);
        assertThat(result.get("ledgerOutId")).isEqualTo(20L);
        assertThat(result.get("ledgerInId")).isEqualTo(21L);
        verify(payPinService).requireSetAndVerify("fan", "123456");
        verify(limitService).check("fan", WalletLimitScene.LIVE_TIP, WalletCurrency.USDT, 1_500_000L);
        verify(ledgerService).debit(eq("fan"), eq(WalletCurrency.USDT), eq(1_500_000L),
            eq(WalletLedgerType.LIVE_TIP_OUT), eq("LIVE_TIP"), eq(8L), eq("anchor"), eq("tip_2"));
        verify(ledgerService).credit(eq("anchor"), eq(WalletCurrency.USDT), eq(1_500_000L),
            eq(WalletLedgerType.LIVE_TIP_IN), eq("LIVE_TIP"), eq(20L), eq("fan"), eq("tip_2"));
        verify(limitService).addDaily("fan", WalletLimitScene.LIVE_TIP, WalletCurrency.USDT, 1_500_000L);
        verify(ledgerService, never()).debit(any(), any(), anyLong(), eq(WalletLedgerType.FEE),
            any(), any(), any(), any());
    }

    @Test
    void conflictWhenClientOrderIdReuseDiffers() {
        when(userRepository.existsByUserId("anchor")).thenReturn(true);
        WalletLedger out = outLedger(10L);
        out.setAmount(-2_000_000L);
        when(ledgerRepository.findFirstByUserIdAndLedgerTypeAndRemarkOrderByIdAsc(
            "fan", WalletLedgerType.LIVE_TIP_OUT, "tip_1")).thenReturn(Optional.of(out));

        assertThatThrownBy(() -> service.tip(
            "fan", "anchor", WalletCurrency.USDT, 1_000_000L, "123456", "tip_1", 9L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("CLIENT_ORDER_ID_CONFLICT");
    }

    private static WalletLedger outLedger(long id) {
        WalletLedger out = new WalletLedger();
        out.setId(id);
        out.setCurrency(WalletCurrency.USDT);
        out.setAmount(-1_500_000L);
        out.setCounterpartUserId("anchor");
        out.setLedgerType(WalletLedgerType.LIVE_TIP_OUT);
        return out;
    }
}
