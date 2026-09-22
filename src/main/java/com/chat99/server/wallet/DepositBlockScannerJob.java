package com.chat99.server.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWalletJobs
public class DepositBlockScannerJob {

    private static final Logger log = LoggerFactory.getLogger(DepositBlockScannerJob.class);

    private final WalletConfigService configService;
    private final TronGridClient tronGrid;
    private final DepositBlockScanCursor blockScanCursor;
    private final DepositAddressBook addressBook;
    private final DepositScanService depositScanService;

    public DepositBlockScannerJob(WalletConfigService configService,
                                    TronGridClient tronGrid,
                                    DepositBlockScanCursor blockScanCursor,
                                    DepositAddressBook addressBook,
                                    DepositScanService depositScanService) {
        this.configService = configService;
        this.tronGrid = tronGrid;
        this.blockScanCursor = blockScanCursor;
        this.addressBook = addressBook;
        this.depositScanService = depositScanService;
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.deposit-block-scan-interval-ms:3000}")
    public void scanBlocks() {
        if (!configService.isBlockScanMode()) {
            return;
        }
        long latest = tronGrid.getCurrentBlockNumberCached();
        if (latest <= 0) {
            return;
        }
        long last = blockScanCursor.get();
        if (last <= 0) {
            blockScanCursor.set(latest - 1);
            return;
        }
        int batch = Math.max(configService.getDepositBlockScanBatchSize(), 1);
        long toScan = Math.min(latest, last + batch);
        long scanned = last;
        for (long block = last + 1; block <= toScan; block++) {
            Optional<List<TronGridClient.Trc20Transfer>> transfersOpt = tronGrid.fetchUsdtTransfersInBlock(block);
            if (transfersOpt.isEmpty()) {
                log.warn("deposit block scan block={} fetch failed, cursor stays at {}", block, scanned);
                break;
            }
            for (TronGridClient.Trc20Transfer transfer : transfersOpt.get()) {
                if (addressBook.resolveUserId(transfer.to()).isPresent()) {
                    walletProcess(transfer);
                }
            }
            scanned = block;
        }
        blockScanCursor.set(scanned);
    }

    private void walletProcess(TronGridClient.Trc20Transfer transfer) {
        if (transfer.to() == null || transfer.to().isBlank()) {
            return;
        }
        try {
            depositScanService.processTransferForAddress(transfer.to(), transfer);
        } catch (Exception e) {
            log.warn("deposit block transfer tx={} err={}", transfer.txId(), e.getMessage());
        }
    }
}
