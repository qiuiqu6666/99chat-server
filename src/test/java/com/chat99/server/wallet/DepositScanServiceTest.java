package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.telegram.TelegramWalletOpsNotifyService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class DepositScanServiceTest {

    @Mock WalletDepositRepository depositRepository;
    @Mock UserWalletRepository walletRepository;
    @Mock WalletLedgerService ledgerService;
    @Mock TronGridClient tronGrid;
    @Mock PlatformWalletNoticeService platformWalletNotice;
    @Mock TelegramWalletOpsNotifyService telegramOpsNotify;
    @Mock WalletChainBalanceService chainBalanceService;
    @Mock DepositHotAddressService hotAddressService;
    @Mock TransactionTemplate transactionTemplate;
    @Mock TransactionStatus transactionStatus;
    @Mock WalletConfigService configService;
    @Mock WalletDepositSweepService depositSweepService;

    private DepositScanService service;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getMinDepositUsdtMicro()).thenReturn(1_000_000L);
        lenient().when(configService.getDepositConfirmations()).thenReturn(19);
        service = new DepositScanService(
            depositRepository, walletRepository, ledgerService, tronGrid, configService,
            platformWalletNotice, telegramOpsNotify, chainBalanceService,
            hotAddressService, transactionTemplate, depositSweepService);
    }

    private void stubTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
    }

    @Test
    void upsertDetectedTransfer_idempotentWhenAlreadyCredited() {
        stubTransactionTemplate();
        TronGridClient.Trc20Transfer transfer = new TronGridClient.Trc20Transfer(
            "tx1", 0, "from", "toAddr", 2_000_000L, 1L, 20);
        UserWallet wallet = new UserWallet();
        wallet.setUserId("user1");
        wallet.setTronAddress("toAddr");
        when(walletRepository.findByTronAddress("toAddr")).thenReturn(Optional.of(wallet));
        WalletDeposit credited = new WalletDeposit();
        credited.setStatus(DepositStatus.CREDITED);
        when(depositRepository.findByTxIdAndLogIndex("tx1", 0)).thenReturn(Optional.of(credited));

        Optional<WalletDeposit> out = service.upsertDetectedTransfer("toAddr", transfer);

        assertThat(out).isEmpty();
        verify(depositRepository, never()).save(any());
    }

    @Test
    void advanceConfirmation_creditsAt19() {
        stubTransactionTemplate();
        WalletDeposit dep = new WalletDeposit();
        dep.setId(10L);
        dep.setUserId("user1");
        dep.setTxId("tx1");
        dep.setToAddress("toAddr");
        dep.setAmountMicro(2_000_000L);
        dep.setStatus(DepositStatus.CONFIRMING);
        dep.setConfirmations(0);

        UserWallet wallet = new UserWallet();
        wallet.setUserId("user1");
        wallet.setTronAddress("toAddr");

        when(depositRepository.findById(10L)).thenReturn(Optional.of(dep));
        when(tronGrid.getTransactionConfirmations("tx1")).thenReturn(19);
        when(walletRepository.findById("user1")).thenReturn(Optional.of(wallet));
        when(depositRepository.save(dep)).thenReturn(dep);

        boolean credited = service.advanceConfirmation(10L);

        assertThat(credited).isTrue();
        assertThat(dep.getStatus()).isEqualTo(DepositStatus.CREDITED);
        verify(tronGrid).getTransactionConfirmations("tx1");
        verify(ledgerService).credit(eq("user1"), eq(WalletCurrency.USDT), eq(2_000_000L),
            eq(WalletLedgerType.DEPOSIT), eq("DEPOSIT"), eq(10L), eq(null), eq("tx1"));
        verify(platformWalletNotice).notifyDeposit("user1", 2_000_000L, "tx1");
        verify(telegramOpsNotify).notifyDepositCredited("user1", "toAddr", 2_000_000L, "tx1");
        verify(chainBalanceService).refreshAndSave(wallet);
        verify(depositSweepService).scheduleAfterDepositCredit("user1");
    }

    @Test
    void advanceConfirmation_alreadyCredited_noOp() {
        WalletDeposit dep = new WalletDeposit();
        dep.setId(11L);
        dep.setStatus(DepositStatus.CREDITED);
        when(depositRepository.findById(11L)).thenReturn(Optional.of(dep));

        boolean credited = service.advanceConfirmation(11L);

        assertThat(credited).isFalse();
        verify(tronGrid, never()).getTransactionConfirmations(anyString());
        verify(transactionTemplate, never()).execute(any());
        verify(ledgerService, never()).credit(anyString(), any(), anyLong(), any(), any(), any(), any(), any());
    }
}
