package com.chat99.server.wallet;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class WithdrawConfirmJob {

    private final WithdrawService withdrawService;
    private final WithdrawProgressPushService progressPushService;

    public WithdrawConfirmJob(WithdrawService withdrawService,
                              WithdrawProgressPushService progressPushService) {
        this.withdrawService = withdrawService;
        this.progressPushService = progressPushService;
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.withdraw-confirm-scan-interval-ms:15000}")
    public void run() {
        withdrawService.confirmBroadcasted();
        progressPushService.retryUnfinishedTerminalPushes();
    }
}
