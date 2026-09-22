package com.chat99.server.wallet;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 资金节点启动时：把 PENDING 且收款地址已是平台用户地址的提现单做内部结清（免审核、DEPOSIT 入账）。
 * 幂等：{@code completeInternal} 对非 PENDING 直接返回。
 */
@Component
@ConditionalOnWalletJobs
public class InternalWithdrawBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InternalWithdrawBackfillRunner.class);

    private final WalletWithdrawalRepository withdrawalRepository;
    private final UserWalletRepository walletRepository;
    private final WithdrawService withdrawService;

    public InternalWithdrawBackfillRunner(WalletWithdrawalRepository withdrawalRepository,
                                          UserWalletRepository walletRepository,
                                          WithdrawService withdrawService) {
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
        this.withdrawService = withdrawService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<WalletWithdrawal> pending = withdrawalRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(WithdrawalStatus.PENDING));
        int settled = 0;
        for (WalletWithdrawal w : pending) {
            if (w.getToAddress() == null || w.getToAddress().isBlank()) {
                continue;
            }
            var dest = walletRepository.findByTronAddress(w.getToAddress());
            if (dest.isEmpty()) {
                continue;
            }
            String recipient = dest.get().getUserId();
            if (w.getUserId().equals(recipient)) {
                log.warn("internal withdraw backfill skip self-address id={} user={}", w.getId(), w.getUserId());
                continue;
            }
            try {
                withdrawService.completeInternal(w, recipient);
                settled++;
                log.info("internal withdraw backfill settled id={} toUser={} amountMicro={}",
                    w.getId(), recipient, w.getPayoutMicro());
            } catch (Exception e) {
                log.error("internal withdraw backfill failed id={} err={}", w.getId(), e.getMessage());
            }
        }
        if (settled > 0 || !pending.isEmpty()) {
            log.info("internal withdraw backfill done pending={} settled={}", pending.size(), settled);
        }
    }
}
