package com.chat99.server.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class WalletSweepJob {

    private static final Logger log = LoggerFactory.getLogger(WalletSweepJob.class);

    private final WalletConfigService configService;
    private final WalletSweepService sweepService;
    private final UserWalletRepository walletRepository;
    private final WalletChainBalanceService chainBalanceService;
    private final WalletSweepLogService sweepLogService;

    public WalletSweepJob(WalletConfigService configService,
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

    @Scheduled(fixedDelayString = "${chat99.wallet.sweep-job-interval-ms:1800000}")
    public void run() {
        boolean enabled = configService.isSweepJobEnabled();
        boolean ready = sweepService.isReady();
        if (!enabled || !ready) {
            log.info("Auto sweep skipped enabled={} ready={}", enabled, ready);
            return;
        }
        int batchSize = Math.max(configService.getSweepJobBatchSize(), 1);
        long minUsdtMicro = configService.getSweepJobMinUsdtMicro();
        var batch = walletRepository.findSweepBatch(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            log.info("Auto sweep skipped empty-batch minUsdtMicro={}", minUsdtMicro);
            return;
        }
        log.info("Auto sweep start scanned={} batchSize={} minUsdtMicro={} firstUserId={} firstCachedUsdtMicro={}",
            batch.size(), batchSize, minUsdtMicro,
            batch.get(0).getUserId(), batch.get(0).getChainUsdtMicro());
        int success = 0;
        int failed = 0;
        int skipped = 0;
        String hotAddress = sweepService.collectAddress();
        log.info("Auto sweep collectTo={}", hotAddress);
        for (UserWallet wallet : batch) {
            try {
                chainBalanceService.refreshAndSave(wallet);
                if (wallet.getChainUsdtMicro() < minUsdtMicro) {
                    skipped++;
                    continue;
                }
                WalletSweepService.SweepResult result = sweepService.sweep(wallet.getUserId(), true, true);
                chainBalanceService.refreshAndSave(wallet);
                sweepLogService.recordSuccess(result, hotAddress, WalletSweepTrigger.AUTO, "system");
                success++;
            } catch (Exception e) {
                failed++;
                sweepLogService.recordFailure(wallet.getUserId(), wallet.getTronAddress(), hotAddress,
                    WalletSweepTrigger.AUTO, "system", e.getMessage());
                log.warn("Auto sweep failed userId={} addr={} err={}",
                    wallet.getUserId(), wallet.getTronAddress(), e.getMessage());
            }
        }
        log.info("Auto sweep round done success={} failed={} skipped={} scanned={}",
            success, failed, skipped, batch.size());
    }
}
