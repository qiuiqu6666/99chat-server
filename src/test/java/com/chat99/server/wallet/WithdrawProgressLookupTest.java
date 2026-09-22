package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.telegram.TelegramWalletOpsNotifyService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawProgressLookupTest {

    @Mock WalletWithdrawalRepository withdrawalRepository;
    @Mock UserWalletRepository walletRepository;
    @Mock WalletDepositRepository depositRepository;
    @Mock WalletLedgerService ledgerService;
    @Mock PayPinService payPinService;
    @Mock WalletLimitService limitService;
    @Mock WalletFeeService feeService;
    @Mock WalletExchangeConfigRepository exchangeConfigRepository;
    @Mock TronGridClient tronGrid;
    @Mock WalletConfigService configService;
    @Mock PlatformWalletNoticeService platformWalletNotice;
    @Mock TelegramWalletOpsNotifyService telegramOpsNotify;
    @Mock WalletTronPrepService tronPrepService;
    @Mock WalletWithdrawLiveActivityRepository liveActivityRepository;
    @Mock WithdrawProgressPushService progressPushService;

    WithdrawService service;

    @BeforeEach
    void setup() {
        service = new WithdrawService(
            withdrawalRepository, walletRepository, depositRepository,
            ledgerService, payPinService, limitService, feeService,
            exchangeConfigRepository, tronGrid, configService, platformWalletNotice,
            telegramOpsNotify, tronPrepService, liveActivityRepository, progressPushService);
        when(walletRepository.findByTronAddress(anyString())).thenReturn(Optional.empty());
        when(configService.getDepositConfirmations()).thenReturn(19);
        when(configService.getMinDepositUsdtMicro()).thenReturn(1_000_000L);
        when(configService.getHotWalletPrivateKey()).thenReturn("");
        when(exchangeConfigRepository.findById(1L)).thenReturn(Optional.empty());
        when(feeService.calculateFee(any(), any(), anyLong())).thenReturn(1_000_000L);
        when(withdrawalRepository.save(any(WalletWithdrawal.class))).thenAnswer(inv -> {
            WalletWithdrawal w = inv.getArgument(0);
            if (w.getId() == null) {
                w.setId(42L);
            }
            return w;
        });
        when(withdrawalRepository.findByUserIdAndClientOrderId(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(liveActivityRepository.findByWithdrawalId(anyLong())).thenReturn(Optional.empty());
        when(liveActivityRepository.save(any(WalletWithdrawLiveActivity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void stageMapping_coversPlanContract() {
        assertThat(WithdrawStage.fromStatus(WithdrawalStatus.PENDING)).isEqualTo(WithdrawStage.SUBMITTED);
        assertThat(WithdrawStage.fromStatus(WithdrawalStatus.BROADCASTING)).isEqualTo(WithdrawStage.BROADCASTING);
        assertThat(WithdrawStage.fromStatus(WithdrawalStatus.CONFIRMING)).isEqualTo(WithdrawStage.CONFIRMING);
        assertThat(WithdrawStage.fromStatus(WithdrawalStatus.COMPLETED)).isEqualTo(WithdrawStage.COMPLETED);
        assertThat(WithdrawStage.fromStatus(WithdrawalStatus.FAILED)).isEqualTo(WithdrawStage.FAILED);
    }

    @Test
    void createAndLookup_byOrderIdAndClientOrderId() {
        WalletWithdrawal created = service.request(
            "user01abcd", "TAjR2bhqd9EKbeH78JUDsPSGGv3ev6TBnz", 12_000_000L, "123456", "WD1734420000123");
        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getClientOrderId()).isEqualTo("WD1734420000123");
        assertThat(created.getStatus()).isEqualTo(WithdrawalStatus.PENDING);

        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(created));
        when(withdrawalRepository.findById(1734420000123L)).thenReturn(Optional.empty());
        when(withdrawalRepository.findByUserIdAndClientOrderId("user01abcd", "WD1734420000123"))
            .thenReturn(Optional.of(created));

        WalletWithdrawal byId = service.requireViewableByRef("user01abcd", "42");
        WalletWithdrawal byWd = service.requireViewableByRef("user01abcd", "WD42");
        WalletWithdrawal byClient = service.requireViewableByRef("user01abcd", "WD1734420000123");
        assertThat(byId.getId()).isEqualTo(byClient.getId());
        assertThat(byWd.getId()).isEqualTo(byClient.getId());

        WithdrawDetailResponse dto = service.toDetail(created);
        assertThat(dto.id()).isEqualTo("42");
        assertThat(dto.clientOrderId()).isEqualTo("WD1734420000123");
        assertThat(dto.status()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(dto.stage()).isEqualTo(WithdrawStage.SUBMITTED);
        assertThat(dto.amount()).isEqualTo(12_000_000L);
        assertThat(dto.fee()).isEqualTo(1_000_000L);
        assertThat(dto.network()).isEqualTo("TRC20");
        assertThat(dto.requiredConfirmations()).isEqualTo(19);
        assertThat(dto.currency()).isEqualTo(WalletCurrency.USDT);
    }

    @Test
    void lookup_otherUserIsNotFound() {
        WalletWithdrawal w = pendingRow();
        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(w));
        assertThatThrownBy(() -> service.requireViewableByRef("otheruser1", "42"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void confirming_reachesRequired_marksCompleted() {
        WalletWithdrawal w = pendingRow();
        w.setStatus(WithdrawalStatus.CONFIRMING);
        w.setTxId("6fb2e6026c2e4d72d5b7af3dd2ad0e91ba2f43987de4f43e621cf3c55a1029af");
        w.setConfirmations(8);
        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(w));
        when(tronGrid.getTransactionConfirmations(anyString())).thenReturn(19);

        WalletWithdrawal out = service.requireViewable("user01abcd", 42L);
        assertThat(out.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(out.getStage()).isEqualTo(WithdrawStage.COMPLETED);
        verify(platformWalletNotice).notifyWithdrawCompleted(eq(w), anyString());
        verify(telegramOpsNotify).notifyWithdrawPaid(w);
        verify(progressPushService).notifyAfterCommit(w);
    }

    @Test
    void confirming_belowRequired_staysConfirming() {
        WalletWithdrawal w = pendingRow();
        w.setStatus(WithdrawalStatus.CONFIRMING);
        w.setTxId("txid");
        w.setConfirmations(3);
        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(w));
        when(tronGrid.getTransactionConfirmations("txid")).thenReturn(8);

        WalletWithdrawal out = service.requireViewable("user01abcd", 42L);
        assertThat(out.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMING);
        assertThat(out.getConfirmations()).isEqualTo(8);
        assertThat(out.getStage()).isEqualTo(WithdrawStage.CONFIRMING);
        verify(platformWalletNotice, never()).notifyWithdrawCompleted(any(), any());
        verify(progressPushService).notifyAfterCommit(w);
    }

    @Test
    void bindLiveActivityToken_lastWriteWins() {
        WalletWithdrawal w = pendingRow();
        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(w));
        WalletWithdrawLiveActivity existing = new WalletWithdrawLiveActivity();
        existing.setId(1L);
        existing.setWithdrawalId(42L);
        existing.setPushToken("old-token");
        when(liveActivityRepository.findByWithdrawalId(42L)).thenReturn(Optional.of(existing));

        service.bindLiveActivityToken(
            "user01abcd", "42", "ios", "A1B2C3D4-E5F6-7890-ABCD-EF1234567890",
            "new-token", "vip.ninechat.pro", "production");

        ArgumentCaptor<WalletWithdrawLiveActivity> captor =
            ArgumentCaptor.forClass(WalletWithdrawLiveActivity.class);
        verify(liveActivityRepository).save(captor.capture());
        assertThat(captor.getValue().getPushToken()).isEqualTo("new-token");
        assertThat(captor.getValue().getActivityId()).isEqualTo("A1B2C3D4-E5F6-7890-ABCD-EF1234567890");
        assertThat(captor.getValue().getPlatform()).isEqualTo("ios");
        assertThat(captor.getValue().getEnvironment()).isEqualTo("production");
    }

    @Test
    void markPaidManually_completesPendingWithoutBroadcast() {
        WalletWithdrawal w = pendingRow();
        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(w));
        String tx = "6fb2e6026c2e4d72d5b7af3dd2ad0e91ba2f43987de4f43e621cf3c55a1029af";

        WalletWithdrawal out = service.markPaidManually(42L, "0x" + tx);
        assertThat(out.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(out.getTxId()).isEqualTo(tx);
        assertThat(out.getConfirmations()).isEqualTo(19);
        verify(platformWalletNotice).notifyWithdrawCompleted(eq(w), eq(tx));
        verify(telegramOpsNotify).notifyWithdrawPaid(w);
        verify(platformWalletNotice, never()).notifyWithdrawApproved(any());
        verify(progressPushService).notifyAfterCommit(w);
    }

    @Test
    void markPaidManually_rejectsBlankAndNonPending() {
        assertThatThrownBy(() -> service.markPaidManually(42L, "  "))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        WalletWithdrawal w = pendingRow();
        w.setStatus(WithdrawalStatus.COMPLETED);
        when(withdrawalRepository.findById(42L)).thenReturn(Optional.of(w));
        assertThatThrownBy(() -> service.markPaidManually(42L,
            "6fb2e6026c2e4d72d5b7af3dd2ad0e91ba2f43987de4f43e621cf3c55a1029af"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    private static WalletWithdrawal pendingRow() {
        WalletWithdrawal w = new WalletWithdrawal();
        w.setId(42L);
        w.setUserId("user01abcd");
        w.setToAddress("TAjR2bhqd9EKbeH78JUDsPSGGv3ev6TBnz");
        w.setAmountMicro(12_000_000L);
        w.setFeeMicro(1_000_000L);
        w.setPayoutMicro(12_000_000L);
        w.setStatus(WithdrawalStatus.PENDING);
        w.setClientOrderId("WD1734420000123");
        w.setConfirmations(0);
        return w;
    }
}
