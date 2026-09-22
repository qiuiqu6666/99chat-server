package com.chat99.server.wallet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class DepositDiscoveryJob {

    private static final Logger log = LoggerFactory.getLogger(DepositDiscoveryJob.class);

    private final DepositScanService depositScanService;
    private final DepositHotAddressService hotAddressService;
    private final DepositColdScanCursor coldScanCursor;
    private final UserWalletRepository walletRepository;
    private final WalletConfigService configService;
    private final Executor depositScanExecutor;

    public DepositDiscoveryJob(DepositScanService depositScanService,
                              DepositHotAddressService hotAddressService,
                              DepositColdScanCursor coldScanCursor,
                              UserWalletRepository walletRepository,
                              WalletConfigService configService,
                              @Qualifier("depositScanExecutor") Executor depositScanExecutor) {
        this.depositScanService = depositScanService;
        this.hotAddressService = hotAddressService;
        this.coldScanCursor = coldScanCursor;
        this.walletRepository = walletRepository;
        this.configService = configService;
        this.depositScanExecutor = depositScanExecutor;
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.deposit-hot-scan-interval-ms:30000}")
    public void hotScan() {
        if (!configService.isAddressPollMode()) {
            return;
        }
        long minTs = minTimestampMs();
        Set<String> hot = hotAddressService.listHotAddresses().stream()
            .collect(Collectors.toSet());
        if (hot.isEmpty()) {
            return;
        }
        runParallel(hot, minTs);
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.deposit-cold-scan-interval-ms:60000}")
    public void coldScan() {
        if (!configService.isAddressPollMode()) {
            return;
        }
        long roundStart = System.currentTimeMillis();
        long minTs = minTimestampMs();
        long cursor = coldScanCursor.get();
        Set<String> hot = hotAddressService.listHotAddresses().stream()
            .collect(Collectors.toSet());
        int batchSize = Math.max(configService.getDepositColdBatchSize(), 1);
        long maxRoundMs = Math.max(configService.getDepositScanMaxRoundMs(), 1000L);

        while (System.currentTimeMillis() - roundStart < maxRoundMs) {
            List<UserWallet> batch = walletRepository.findEligibleForDepositScanAfterDerivationIndex(
                cursor, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                coldScanCursor.reset();
                break;
            }
            List<String> coldAddresses = new ArrayList<>();
            for (UserWallet wallet : batch) {
                cursor = wallet.getDerivationIndex();
                if (hot.contains(wallet.getTronAddress())) {
                    continue;
                }
                coldAddresses.add(wallet.getTronAddress());
            }
            if (!coldAddresses.isEmpty()) {
                long remainingMs = maxRoundMs - (System.currentTimeMillis() - roundStart);
                awaitColdScans(coldAddresses, minTs, remainingMs);
            }
            coldScanCursor.set(cursor);
            if (batch.size() < batchSize) {
                coldScanCursor.reset();
                break;
            }
        }
    }

    private long minTimestampMs() {
        int days = Math.max(configService.getDepositColdLookbackDays(), 1);
        return Instant.now().minusSeconds(86400L * days).toEpochMilli();
    }

    private void awaitColdScans(List<String> addresses, long minTs, long timeoutMs) {
        if (timeoutMs <= 0 || addresses.isEmpty()) {
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(addresses.size());
        for (String address : addresses) {
            depositScanExecutor.execute(() -> {
                try {
                    depositScanService.processAddressForDiscovery(address, minTs);
                } catch (Exception e) {
                    log.warn("deposit cold scan address={} err={}", address, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runParallel(Set<String> addresses, long minTs) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(addresses.size());
        for (String address : addresses) {
            depositScanExecutor.execute(() -> {
                try {
                    depositScanService.processAddressForDiscovery(address, minTs);
                } catch (Exception e) {
                    log.warn("deposit hot scan address={} err={}", address, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
