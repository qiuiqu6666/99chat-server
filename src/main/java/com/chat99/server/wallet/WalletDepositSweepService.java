package com.chat99.server.wallet;

import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 充值入账后异步归集链上 USDT，不阻塞入账事务。
 */
@Service
public class WalletDepositSweepService {

    private static final Logger log = LoggerFactory.getLogger(WalletDepositSweepService.class);

    private final WalletConfigService configService;
    private final WalletSweepService sweepService;
    private final UserWalletRepository walletRepository;
    private final WalletChainBalanceService chainBalanceService;
    private final WalletSweepLogService sweepLogService;

    public WalletDepositSweepService(WalletConfigService configService,
                                     WalletSweepService sweepService,
                                     UserWalletRepository walletRepository,
                                     WalletChainBalanceService chainBalanceService,
                                     WalletSweepLogService sweepLogService) {
        this.configService = configService;
        this.sweepService = sweepService;
        this.walletRepository = walletRepository;
        this.chainBalanceService = chainBalanceService;
        this.sweepLogService = sweepLogService;
    }

    public void scheduleAfterDepositCredit(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runAsync(userId);
                }
            });
            return;
        }
        runAsync(userId);
    }

    private void runAsync(String userId) {
        CompletableFuture.runAsync(() -> sweepAfterDeposit(userId));
    }

    void sweepAfterDeposit(String userId) {
        boolean enabled = configService.isSweepJobEnabled();
        boolean ready = sweepService.isReady();
        if (!enabled || !ready) {
            log.info("Deposit sweep skipped userId={} enabled={} ready={}", userId, enabled, ready);
            return;
        }
        UserWallet wallet = walletRepository.findById(userId).orElse(null);
        if (wallet == null) {
            return;
        }
        String collectAddress = sweepService.collectAddress();
        try {
            chainBalanceService.refreshAndSave(wallet);
            if (wallet.getChainUsdtMicro() <= 0) {
                log.info("Deposit sweep skipped userId={} chainUsdtMicro=0", userId);
                return;
            }
            log.info("Deposit sweep start userId={} addr={} chainUsdtMicro={} collectTo={}",
                userId, wallet.getTronAddress(), wallet.getChainUsdtMicro(), collectAddress);
            WalletSweepService.SweepResult result = sweepService.sweep(userId, true, true);
            chainBalanceService.refreshAndSave(wallet);
            sweepLogService.recordSuccess(result, collectAddress, WalletSweepTrigger.DEPOSIT, "deposit");
            log.info("Deposit sweep done userId={} usdtMicro={} tx={}",
                userId, result.usdtSweptMicro(), result.usdtTxId());
        } catch (Exception e) {
            sweepLogService.recordFailure(userId, wallet.getTronAddress(), collectAddress,
                WalletSweepTrigger.DEPOSIT, "deposit", e.getMessage());
            log.warn("Deposit sweep failed userId={} addr={} err={}",
                userId, wallet.getTronAddress(), e.getMessage());
        }
    }
}
