package com.chat99.server.wallet;

import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.telegram.TelegramWalletOpsNotifyService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WithdrawService {

    private final WalletWithdrawalRepository withdrawalRepository;
    private final UserWalletRepository walletRepository;
    private final WalletDepositRepository depositRepository;
    private final WalletLedgerService ledgerService;
    private final PayPinService payPinService;
    private final WalletLimitService limitService;
    private final WalletFeeService feeService;
    private final WalletExchangeConfigRepository exchangeConfigRepository;
    private final TronGridClient tronGrid;
    private final WalletConfigService configService;
    private final PlatformWalletNoticeService platformWalletNotice;
    private final TelegramWalletOpsNotifyService telegramOpsNotify;
    private final WalletTronPrepService tronPrepService;
    private final WalletWithdrawLiveActivityRepository liveActivityRepository;
    private final WithdrawProgressPushService progressPushService;

    public WithdrawService(WalletWithdrawalRepository withdrawalRepository,
                           UserWalletRepository walletRepository,
                           WalletDepositRepository depositRepository,
                           WalletLedgerService ledgerService, PayPinService payPinService,
                           WalletLimitService limitService, WalletFeeService feeService,
                           WalletExchangeConfigRepository exchangeConfigRepository,
                           TronGridClient tronGrid, WalletConfigService configService,
                           PlatformWalletNoticeService platformWalletNotice,
                           TelegramWalletOpsNotifyService telegramOpsNotify,
                           WalletTronPrepService tronPrepService,
                           WalletWithdrawLiveActivityRepository liveActivityRepository,
                           WithdrawProgressPushService progressPushService) {
        this.withdrawalRepository = withdrawalRepository;
        this.walletRepository = walletRepository;
        this.depositRepository = depositRepository;
        this.ledgerService = ledgerService;
        this.payPinService = payPinService;
        this.limitService = limitService;
        this.feeService = feeService;
        this.exchangeConfigRepository = exchangeConfigRepository;
        this.tronGrid = tronGrid;
        this.configService = configService;
        this.platformWalletNotice = platformWalletNotice;
        this.telegramOpsNotify = telegramOpsNotify;
        this.tronPrepService = tronPrepService;
        this.liveActivityRepository = liveActivityRepository;
        this.progressPushService = progressPushService;
    }

    @Transactional
    public WalletWithdrawal request(String userId, String toAddress, long amountMicro, String payPin) {
        return request(userId, toAddress, amountMicro, payPin, null);
    }

    @Transactional
    public WalletWithdrawal request(String userId, String toAddress, long amountMicro, String payPin,
                                    String clientOrderId) {
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            var existing = withdrawalRepository.findByUserIdAndClientOrderId(userId, clientOrderId.trim());
            if (existing.isPresent()) {
                WalletWithdrawal w = existing.get();
                if (!w.getToAddress().equals(toAddress == null ? "" : toAddress.trim())
                    || w.getAmountMicro() != amountMicro) {
                    throw WalletExceptions.of(HttpStatus.CONFLICT, "CLIENT_ORDER_ID_CONFLICT");
                }
                return w;
            }
        }
        String addr = toAddress == null ? "" : toAddress.trim();
        if (addr.isBlank() || addr.length() > 256 || addr.matches(".*[\\x00-\\x1F\\x7F].*")) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_TRON_ADDRESS");
        }
        Optional<UserWallet> destWallet = walletRepository.findByTronAddress(addr);
        if (destWallet.isPresent() && userId.equals(destWallet.get().getUserId())) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "WITHDRAW_TO_SELF");
        }
        boolean internal = destWallet.isPresent();
        WalletExchangeConfig cfg = exchangeConfigRepository.findById(1L).orElse(null);
        long min = cfg == null ? configService.getMinDepositUsdtMicro() : cfg.getMinWithdrawUsdtMicro();
        if (amountMicro < min) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "WITHDRAW_MIN_NOT_MET");
        }
        payPinService.requireSetAndVerify(userId, payPin);
        limitService.check(userId, WalletLimitScene.WITHDRAW, WalletCurrency.USDT, amountMicro);
        long fee = internal ? 0L : feeService.calculateFee(WalletFeeScene.WITHDRAW, WalletCurrency.USDT, amountMicro);
        long totalDebit = amountMicro + fee;

        WalletWithdrawal w = new WalletWithdrawal();
        w.setUserId(userId);
        w.setToAddress(addr);
        w.setAmountMicro(amountMicro);
        w.setFeeMicro(fee);
        w.setPayoutMicro(amountMicro);
        w.setStatus(WithdrawalStatus.PENDING);
        w.setConfirmations(0);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            w.setClientOrderId(clientOrderId.trim());
        }
        withdrawalRepository.save(w);

        ledgerService.debit(userId, WalletCurrency.USDT, totalDebit, WalletLedgerType.WITHDRAW,
            "WITHDRAW", w.getId(), null, addr);
        limitService.addDaily(userId, WalletLimitScene.WITHDRAW, WalletCurrency.USDT, totalDebit);
        telegramOpsNotify.notifyWithdrawRequested(w);
        if (internal) {
            return completeInternal(w, destWallet.get().getUserId());
        }
        if (configService.isWithdrawAutoMode() && configService.isHotWalletConfigured()) {
            return approveById(w.getId());
        }
        return w;
    }

    public WalletWithdrawal requireViewableByRef(String viewerUserId, String idOrClient) {
        if (idOrClient == null || idOrClient.isBlank()) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND");
        }
        String raw = idOrClient.trim();
        String ref = normalizeWithdrawRef(raw);
        if (isNumericId(ref)) {
            try {
                return requireViewable(viewerUserId, Long.parseLong(ref));
            } catch (ResponseStatusException ex) {
                // WD{timestamp} 形态的 clientOrderId 会被误判成主键，回退按幂等号查
                if (ex.getStatusCode() != HttpStatus.NOT_FOUND || isNumericId(raw)) {
                    throw ex;
                }
                return requireViewableByClientOrderId(viewerUserId, raw);
            }
        }
        return requireViewableByClientOrderId(viewerUserId, raw);
    }

    public WalletWithdrawal requireViewable(String viewerUserId, long id) {
        WalletWithdrawal w = withdrawalRepository.findById(id)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND"));
        if (!viewerUserId.equals(w.getUserId())) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND");
        }
        return refreshConfirmations(w);
    }

    public WalletWithdrawal requireViewableByClientOrderId(String viewerUserId, String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND");
        }
        WalletWithdrawal w = withdrawalRepository.findByUserIdAndClientOrderId(viewerUserId, clientOrderId.trim())
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND"));
        return refreshConfirmations(w);
    }

    public WithdrawDetailResponse toDetail(WalletWithdrawal w) {
        return WithdrawDetailResponse.from(w, requiredConfirmations(), hotWalletAddressOrNull());
    }

    @Transactional
    public void bindLiveActivityToken(String userId, String orderRef, String platform, String activityId,
                                      String pushToken, String bundleId, String environment) {
        WalletWithdrawal w = requireViewableByRef(userId, orderRef);
        if (pushToken == null || pushToken.isBlank() || activityId == null || activityId.isBlank()) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String plat = platform == null || platform.isBlank() ? "ios" : platform.trim().toLowerCase();
        WalletWithdrawLiveActivity row = liveActivityRepository.findByWithdrawalId(w.getId())
            .orElseGet(WalletWithdrawLiveActivity::new);
        row.setWithdrawalId(w.getId());
        row.setUserId(userId);
        row.setPlatform(plat);
        row.setActivityId(activityId.trim());
        row.setPushToken(pushToken.trim());
        row.setBundleId(bundleId);
        row.setEnvironment(environment);
        liveActivityRepository.save(row);
    }

    @Transactional
    public void broadcastPending() {
        if (!configService.isHotWalletConfigured()) {
            return;
        }
        if (!configService.isWithdrawAutoMode()) {
            return;
        }
        for (WalletWithdrawal w : withdrawalRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(WithdrawalStatus.PENDING))) {
            broadcastOne(w);
            notifyPayoutResult(withdrawalRepository.findById(w.getId()).orElse(w));
        }
    }

    @Transactional
    public void confirmBroadcasted() {
        for (WalletWithdrawal w : withdrawalRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(WithdrawalStatus.CONFIRMING))) {
            refreshConfirmations(w);
        }
    }

    @Transactional
    public WalletWithdrawal approveById(long id) {
        WalletWithdrawal w = withdrawalRepository.findById(id)
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND"));
        if (w.getStatus() != WithdrawalStatus.PENDING) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "WITHDRAW_STATUS_INVALID");
        }
        Optional<UserWallet> destWallet = walletRepository.findByTronAddress(w.getToAddress());
        if (destWallet.isPresent()) {
            String recipient = destWallet.get().getUserId();
            if (w.getUserId().equals(recipient)) {
                throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "WITHDRAW_TO_SELF");
            }
            return completeInternal(w, recipient);
        }
        if (!configService.isHotWalletConfigured()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "HOT_WALLET_NOT_CONFIGURED");
        }
        platformWalletNotice.notifyWithdrawApproved(w);
        telegramOpsNotify.notifyWithdrawApproved(w);
        broadcastOne(w);
        WalletWithdrawal latest = withdrawalRepository.findById(id).orElse(w);
        notifyPayoutResult(latest);
        return latest;
    }

    /**
     * 提现目标地址命中平台用户：免审核、手续费归零（历史已扣则退回），写合成充值单并 DEPOSIT 入账，
     * 提现单直接 COMPLETED（txId 保持 null，不上链）。
     */
    @Transactional
    public WalletWithdrawal completeInternal(WalletWithdrawal w, String recipientUserId) {
        if (w == null || w.getId() == null) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND");
        }
        if (w.getStatus() != WithdrawalStatus.PENDING) {
            return w;
        }
        if (recipientUserId == null || recipientUserId.isBlank() || w.getUserId().equals(recipientUserId)) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "WITHDRAW_TO_SELF");
        }
        long fee = w.getFeeMicro();
        if (fee > 0) {
            ledgerService.credit(w.getUserId(), WalletCurrency.USDT, fee, WalletLedgerType.WITHDRAW,
                "WITHDRAW_REFUND", w.getId(), null, "internal fee waived");
            w.setFeeMicro(0);
        }
        Instant now = Instant.now();
        String syntheticTxId = internalDepositTxId(w.getId());
        WalletDeposit dep = new WalletDeposit();
        dep.setUserId(recipientUserId);
        dep.setTxId(syntheticTxId);
        dep.setLogIndex(0);
        dep.setFromAddress(walletRepository.findById(w.getUserId()).map(UserWallet::getTronAddress).orElse(null));
        dep.setToAddress(w.getToAddress());
        dep.setAmountMicro(w.getPayoutMicro());
        dep.setConfirmations(requiredConfirmations());
        dep.setStatus(DepositStatus.CREDITED);
        dep.setBlockTimestamp(now);
        dep.setCreditedAt(now);
        depositRepository.save(dep);

        ledgerService.credit(recipientUserId, WalletCurrency.USDT, w.getPayoutMicro(),
            WalletLedgerType.DEPOSIT, "DEPOSIT", dep.getId(), w.getUserId(),
            "internal-withdraw:" + w.getId());

        w.setStatus(WithdrawalStatus.COMPLETED);
        w.setTxId(null);
        w.setConfirmations(requiredConfirmations());
        w.setCompletedAt(now);
        withdrawalRepository.save(w);

        platformWalletNotice.notifyWithdrawCompleted(w, null);
        platformWalletNotice.notifyDeposit(recipientUserId, w.getPayoutMicro(), syntheticTxId);
        telegramOpsNotify.notifyWithdrawPaid(w);
        telegramOpsNotify.notifyDepositCredited(recipientUserId, w.getToAddress(), w.getPayoutMicro(), syntheticTxId);
        progressPushService.notifyAfterCommit(w);
        return w;
    }

    static String internalDepositTxId(long withdrawalId) {
        return String.format("IW%062d", withdrawalId);
    }

    /**
     * 运营已从自有地址链上打款：只回写 Tx 并结单，不再热钱包广播（避免重复出款）。
     */
    @Transactional
    public WalletWithdrawal markPaidManually(long id, String txId) {
        String tx = normalizeTxId(txId);
        WalletWithdrawal w = withdrawalRepository.findById(id)
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND"));
        if (w.getStatus() != WithdrawalStatus.PENDING) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "WITHDRAW_STATUS_INVALID");
        }
        w.setTxId(tx);
        w.setConfirmations(Math.max(w.getConfirmations(), requiredConfirmations()));
        markCompleted(w);
        return w;
    }

    static String normalizeTxId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "TX_ID_REQUIRED");
        }
        String tx = raw.trim();
        if (tx.startsWith("0x") || tx.startsWith("0X")) {
            tx = tx.substring(2);
        }
        if (tx.length() != 64) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_TX_ID");
        }
        for (int i = 0; i < tx.length(); i++) {
            char c = tx.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (!hex) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_TX_ID");
            }
        }
        return tx;
    }

    @Transactional
    public WalletWithdrawal rejectById(long id, String reason) {
        WalletWithdrawal w = withdrawalRepository.findById(id)
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "WITHDRAW_NOT_FOUND"));
        if (w.getStatus() != WithdrawalStatus.PENDING) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "WITHDRAW_STATUS_INVALID");
        }
        String failReason = reason == null || reason.isBlank() ? "管理员拒绝" : reason.trim();
        w.setStatus(WithdrawalStatus.FAILED);
        w.setFailReason(failReason);
        w.setCompletedAt(java.time.Instant.now());
        withdrawalRepository.save(w);
        refund(w);
        platformWalletNotice.notifyWithdrawFailed(w, failReason);
        telegramOpsNotify.notifyWithdrawRejected(w, failReason);
        progressPushService.notifyAfterCommit(w);
        return w;
    }

    private void broadcastOne(WalletWithdrawal w) {
        w.setStatus(WithdrawalStatus.BROADCASTING);
        withdrawalRepository.save(w);
        try {
            String hotAddress = TronTransactionSigner.privateKeyToBase58Address(
                configService.getHotWalletPrivateKey().trim());
            tronPrepService.prepareHotWallet(hotAddress);
            String txId = tronGrid.broadcastUsdtTransfer(
                configService.getHotWalletPrivateKey().trim(), w.getToAddress(), w.getPayoutMicro());
            w.setTxId(txId);
            w.setStatus(WithdrawalStatus.CONFIRMING);
            w.setConfirmations(0);
            withdrawalRepository.save(w);
        } catch (Exception e) {
            w.setStatus(WithdrawalStatus.FAILED);
            w.setFailReason(e.getMessage());
            w.setCompletedAt(java.time.Instant.now());
            withdrawalRepository.save(w);
            refund(w);
            platformWalletNotice.notifyWithdrawFailed(w, e.getMessage());
        }
        progressPushService.notifyAfterCommit(w);
    }

    WalletWithdrawal refreshConfirmations(WalletWithdrawal w) {
        if (w == null || w.getStatus() != WithdrawalStatus.CONFIRMING) {
            return w;
        }
        if (w.getTxId() == null || w.getTxId().isBlank()) {
            return w;
        }
        try {
            int conf = Math.max(0, tronGrid.getTransactionConfirmations(w.getTxId()));
            boolean confChanged = conf > w.getConfirmations();
            if (confChanged) {
                w.setConfirmations(conf);
            }
            if (w.getConfirmations() >= requiredConfirmations()) {
                markCompleted(w);
            } else if (confChanged) {
                withdrawalRepository.save(w);
                progressPushService.notifyAfterCommit(w);
            }
        } catch (Exception ignored) {
            // 查确认数失败不改终态，下次扫描再试
        }
        return w;
    }

    private void markCompleted(WalletWithdrawal w) {
        if (w.getStatus() == WithdrawalStatus.COMPLETED) {
            return;
        }
        w.setStatus(WithdrawalStatus.COMPLETED);
        w.setCompletedAt(java.time.Instant.now());
        withdrawalRepository.save(w);
        platformWalletNotice.notifyWithdrawCompleted(w, w.getTxId());
        telegramOpsNotify.notifyWithdrawPaid(w);
        progressPushService.notifyAfterCommit(w);
    }

    private void notifyPayoutResult(WalletWithdrawal w) {
        if (w == null || w.getStatus() == null) {
            return;
        }
        if (w.getStatus() == WithdrawalStatus.FAILED) {
            telegramOpsNotify.notifyWithdrawPayoutFailed(w, w.getFailReason());
        }
    }

    private void refund(WalletWithdrawal w) {
        ledgerService.credit(w.getUserId(), WalletCurrency.USDT, w.getAmountMicro() + w.getFeeMicro(),
            WalletLedgerType.WITHDRAW, "WITHDRAW_REFUND", w.getId(), null, w.getFailReason());
    }

    public int requiredConfirmations() {
        int n = configService.getDepositConfirmations();
        return n > 0 ? n : 19;
    }

    private String hotWalletAddressOrNull() {
        try {
            String key = configService.getHotWalletPrivateKey();
            if (key == null || key.isBlank()) {
                return null;
            }
            return TronTransactionSigner.privateKeyToBase58Address(key.trim());
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizeWithdrawRef(String raw) {
        String ref = raw.trim();
        if (ref.length() > 2 && (ref.startsWith("WD") || ref.startsWith("wd"))
            && isNumericId(ref.substring(2))) {
            return ref.substring(2);
        }
        return ref;
    }

    static boolean isNumericId(String ref) {
        if (ref == null || ref.isEmpty() || ref.length() > 18) {
            return false;
        }
        for (int i = 0; i < ref.length(); i++) {
            if (!Character.isDigit(ref.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
