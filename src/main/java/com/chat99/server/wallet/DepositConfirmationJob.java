package com.chat99.server.wallet;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class DepositConfirmationJob {

    private static final Logger log = LoggerFactory.getLogger(DepositConfirmationJob.class);
    private static final int BATCH_SIZE = 500;

    private final WalletDepositRepository depositRepository;
    private final DepositScanService depositScanService;

    public DepositConfirmationJob(WalletDepositRepository depositRepository,
                                  DepositScanService depositScanService) {
        this.depositRepository = depositRepository;
        this.depositScanService = depositScanService;
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.deposit-confirm-scan-interval-ms:12000}")
    public void confirmPending() {
        List<WalletDeposit> pending = depositRepository.findByStatusOrderByCreatedAtAsc(
            DepositStatus.CONFIRMING, PageRequest.of(0, BATCH_SIZE));
        for (WalletDeposit dep : pending) {
            try {
                depositScanService.advanceConfirmation(dep.getId());
            } catch (Exception e) {
                log.warn("deposit confirm failed id={} err={}", dep.getId(), e.getMessage());
            }
        }
    }
}
