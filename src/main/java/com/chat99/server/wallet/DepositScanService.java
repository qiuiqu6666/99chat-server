package com.chat99.server.wallet;

import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.telegram.TelegramWalletOpsNotifyService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DepositScanService {

    private final WalletDepositRepository depositRepository;
    private final UserWalletRepository walletRepository;
    private final WalletLedgerService ledgerService;
    private final TronGridClient tronGrid;
    private final WalletConfigService configService;
    private final PlatformWalletNoticeService platformWalletNotice;
    private final TelegramWalletOpsNotifyService telegramOpsNotify;
    private final WalletChainBalanceService chainBalanceService;
    private final DepositHotAddressService hotAddressService;
    private final TransactionTemplate transactionTemplate;
    private final WalletDepositSweepService depositSweepService;

    public DepositScanService(WalletDepositRepository depositRepository,
                              UserWalletRepository walletRepository,
                              WalletLedgerService ledgerService,
                              TronGridClient tronGrid,
                              WalletConfigService configService,
                              PlatformWalletNoticeService platformWalletNotice,
                              TelegramWalletOpsNotifyService telegramOpsNotify,
                              WalletChainBalanceService chainBalanceService,
                              DepositHotAddressService hotAddressService,
                              TransactionTemplate transactionTemplate,
                              WalletDepositSweepService depositSweepService) {
        this.depositRepository = depositRepository;
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
        this.tronGrid = tronGrid;
        this.configService = configService;
        this.platformWalletNotice = platformWalletNotice;
        this.telegramOpsNotify = telegramOpsNotify;
        this.chainBalanceService = chainBalanceService;
        this.hotAddressService = hotAddressService;
        this.transactionTemplate = transactionTemplate;
        this.depositSweepService = depositSweepService;
    }

    public List<TronGridClient.Trc20Transfer> fetchInboundTransfers(String tronAddress, long minTsMs) {
        return tronGrid.fetchRecentUsdtTransfersTo(tronAddress, minTsMs);
    }

    public void processAddressForDiscovery(String tronAddress, long minTsMs) {
        for (TronGridClient.Trc20Transfer transfer : fetchInboundTransfers(tronAddress, minTsMs)) {
            upsertDetectedTransfer(tronAddress, transfer).ifPresent(dep -> {
                hotAddressService.markHot(tronAddress);
                advanceConfirmation(dep.getId());
            });
        }
    }

    public void processTransferForAddress(String tronAddress, TronGridClient.Trc20Transfer transfer) {
        upsertDetectedTransfer(tronAddress, transfer).ifPresent(dep -> {
            hotAddressService.markHot(tronAddress);
            advanceConfirmation(dep.getId());
        });
    }

    public DepositReportResult processTransferByTxId(String userId, String txId) {
        UserWallet wallet = walletRepository.findById(userId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
        TronGridClient.Trc20Transfer transfer = tronGrid.fetchUsdtTransferByTxId(txId, wallet.getTronAddress())
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.BAD_REQUEST, "TX_NOT_FOUND"));
        if (transfer.amountMicro() < configService.getMinDepositUsdtMicro()) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "BELOW_MIN_DEPOSIT");
        }
        WalletDeposit dep = upsertDetectedTransfer(wallet.getTronAddress(), transfer)
            .orElseGet(() -> depositRepository.findByTxIdAndLogIndex(transfer.txId(), transfer.logIndex())
                .orElseThrow(() -> WalletExceptions.of(HttpStatus.BAD_REQUEST, "TX_NOT_FOR_USER")));
        hotAddressService.markHot(wallet.getTronAddress());
        advanceConfirmation(dep.getId());
        WalletDeposit fresh = depositRepository.findById(dep.getId()).orElse(dep);
        return new DepositReportResult(true, fresh.getStatus().name());
    }

    public Optional<WalletDeposit> upsertDetectedTransfer(String tronAddress, TronGridClient.Trc20Transfer transfer) {
        return transactionTemplate.execute(status -> upsertDetectedTransferTx(tronAddress, transfer));
    }

    public boolean advanceConfirmation(long depositId) {
        WalletDeposit dep = depositRepository.findById(depositId).orElse(null);
        if (dep == null || dep.getStatus() == DepositStatus.CREDITED) {
            return false;
        }
        int conf = Math.max(dep.getConfirmations(), tronGrid.getTransactionConfirmations(dep.getTxId()));
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> applyConfirmationTx(depositId, conf)));
    }

    private Optional<WalletDeposit> upsertDetectedTransferTx(String tronAddress, TronGridClient.Trc20Transfer transfer) {
        if (transfer.amountMicro() < configService.getMinDepositUsdtMicro()) {
            return Optional.empty();
        }
        UserWallet wallet = walletRepository.findByTronAddress(tronAddress).orElse(null);
        if (wallet == null) {
            return Optional.empty();
        }
        Optional<WalletDeposit> existing = depositRepository.findByTxIdAndLogIndex(transfer.txId(), transfer.logIndex());
        if (existing.isPresent()) {
            WalletDeposit dep = existing.get();
            if (dep.getStatus() == DepositStatus.CREDITED) {
                return Optional.empty();
            }
            dep.setConfirmations(Math.max(dep.getConfirmations(), transfer.confirmations()));
            dep.setStatus(DepositStatus.CONFIRMING);
            depositRepository.save(dep);
            return Optional.of(dep);
        }
        WalletDeposit dep = new WalletDeposit();
        dep.setUserId(wallet.getUserId());
        dep.setTxId(transfer.txId());
        dep.setLogIndex(transfer.logIndex());
        dep.setFromAddress(transfer.from());
        dep.setToAddress(transfer.to());
        dep.setAmountMicro(transfer.amountMicro());
        dep.setConfirmations(transfer.confirmations());
        dep.setStatus(DepositStatus.CONFIRMING);
        dep.setBlockTimestamp(transfer.blockTimestamp() > 0
            ? Instant.ofEpochMilli(transfer.blockTimestamp()) : null);
        return Optional.of(depositRepository.save(dep));
    }

    private boolean applyConfirmationTx(long depositId, int conf) {
        WalletDeposit dep = depositRepository.findById(depositId).orElse(null);
        if (dep == null || dep.getStatus() == DepositStatus.CREDITED) {
            return false;
        }
        dep.setConfirmations(Math.max(dep.getConfirmations(), conf));
        if (dep.getConfirmations() >= configService.getDepositConfirmations()) {
            UserWallet wallet = walletRepository.findById(dep.getUserId())
                .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
            creditDeposit(dep, wallet);
            return true;
        }
        dep.setStatus(DepositStatus.CONFIRMING);
        depositRepository.save(dep);
        hotAddressService.markHot(dep.getToAddress());
        return false;
    }

    private void creditDeposit(WalletDeposit dep, UserWallet wallet) {
        if (dep.getStatus() == DepositStatus.CREDITED) {
            return;
        }
        dep.setStatus(DepositStatus.CREDITED);
        dep.setCreditedAt(Instant.now());
        depositRepository.save(dep);
        ledgerService.credit(wallet.getUserId(), WalletCurrency.USDT, dep.getAmountMicro(),
            WalletLedgerType.DEPOSIT, "DEPOSIT", dep.getId(), null, dep.getTxId());
        platformWalletNotice.notifyDeposit(wallet.getUserId(), dep.getAmountMicro(), dep.getTxId());
        telegramOpsNotify.notifyDepositCredited(
            wallet.getUserId(), wallet.getTronAddress(), dep.getAmountMicro(), dep.getTxId());
        try {
            chainBalanceService.refreshAndSave(wallet);
        } catch (Exception ignored) {
            // 链上余额同步失败不影响入账
        }
        depositSweepService.scheduleAfterDepositCredit(wallet.getUserId());
    }

    public record DepositReportResult(boolean ok, String status) {}
}
