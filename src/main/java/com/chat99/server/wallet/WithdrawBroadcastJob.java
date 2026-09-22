package com.chat99.server.wallet;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class WithdrawBroadcastJob {

    private final WithdrawService withdrawService;

    public WithdrawBroadcastJob(WithdrawService withdrawService) {
        this.withdrawService = withdrawService;
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.withdraw-broadcast-interval-ms:15000}")
    public void run() {
        withdrawService.broadcastPending();
    }
}
