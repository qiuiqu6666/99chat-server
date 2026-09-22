package com.chat99.server.wallet;

import com.chat99.server.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WalletTransferService {

    private final UserRepository userRepository;
    private final WalletTransferRepository transferRepository;
    private final WalletLedgerService ledgerService;
    private final PayPinService payPinService;
    private final WalletLimitService limitService;
    private final WalletFeeService feeService;
    private final WalletPlatformStatsRepository statsRepository;
    private final WalletOrderCardReadCache cardReadCache;

    public WalletTransferService(UserRepository userRepository, WalletTransferRepository transferRepository,
                                 WalletLedgerService ledgerService, PayPinService payPinService,
                                 WalletLimitService limitService, WalletFeeService feeService,
                                 WalletPlatformStatsRepository statsRepository,
                                 WalletOrderCardReadCache cardReadCache) {
        this.userRepository = userRepository;
        this.transferRepository = transferRepository;
        this.ledgerService = ledgerService;
        this.payPinService = payPinService;
        this.limitService = limitService;
        this.feeService = feeService;
        this.statsRepository = statsRepository;
        this.cardReadCache = cardReadCache;
    }

    public WalletTransfer requireViewableTransfer(long transferId, String viewerUserId) {
        WalletTransfer t = transferRepository.findById(transferId)
            .or(() -> cardReadCache.findTransferById(transferId))
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND"));
        assertViewer(t, viewerUserId);
        return t;
    }

    /**
     * 路径 ID 同时支持服务端 orderId 与 clientOrderId；格式无法识别时查缓存/库，不返回 INVALID_INPUT。
     */
    public WalletTransfer requireViewableByRef(String viewerUserId, String idOrClient) {
        if (idOrClient == null || idOrClient.isBlank()) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND");
        }
        String ref = idOrClient.trim();
        if (isNumericId(ref)) {
            return requireViewableTransfer(Long.parseLong(ref), viewerUserId);
        }
        return requireViewableByClientOrderId(viewerUserId, ref);
    }

    public WalletTransfer requireViewableByClientOrderId(String viewerUserId, String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND");
        }
        String cid = clientOrderId.trim();
        WalletTransfer t = transferRepository.findByFromUserIdAndClientOrderId(viewerUserId, cid)
            .or(() -> transferRepository.findByToUserIdAndClientOrderId(viewerUserId, cid))
            .or(() -> firstInvolving(transferRepository.findByClientOrderId(cid), viewerUserId))
            .or(() -> cardReadCache.findTransferByClientOrderId(cid)
                .filter(cached -> isViewer(cached, viewerUserId)))
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND"));
        assertViewer(t, viewerUserId);
        return t;
    }

    @Transactional
    public WalletTransfer transfer(String fromUserId, String toUserId, WalletCurrency currency,
                                   long amount, String payPin, String memo, String clientOrderId) {
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            var existing = transferRepository.findByFromUserIdAndClientOrderId(fromUserId, clientOrderId);
            if (existing.isPresent()) {
                WalletTransfer t = existing.get();
                if (!t.getToUserId().equals(toUserId) || t.getCurrency() != currency || t.getAmount() != amount) {
                    throw WalletExceptions.of(HttpStatus.CONFLICT, "CLIENT_ORDER_ID_CONFLICT");
                }
                rememberTransfer(t);
                return t;
            }
        }
        if (fromUserId.equals(toUserId)) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_RECIPIENT");
        }
        if (!userRepository.existsByUserId(toUserId)) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
        }
        payPinService.requireSetAndVerify(fromUserId, payPin);
        limitService.check(fromUserId, WalletLimitScene.TRANSFER, currency, amount);

        WalletFeeScene feeScene = currency == WalletCurrency.USDT
            ? null : WalletFeeScene.TRANSFER_PLATFORM;
        long fee = feeScene == null ? 0 : feeService.calculateFee(feeScene, currency, amount);

        WalletTransfer t = new WalletTransfer();
        t.setFromUserId(fromUserId);
        t.setToUserId(toUserId);
        t.setCurrency(currency);
        t.setAmount(amount);
        t.setFeeAmount(fee);
        t.setMemo(memo);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            t.setClientOrderId(clientOrderId);
        }
        t.setStatus(WalletTransferStatus.PROCESSING);
        transferRepository.save(t);
        rememberTransfer(t);

        ledgerService.debit(fromUserId, currency, amount, WalletLedgerType.TRANSFER_OUT,
            "TRANSFER", t.getId(), toUserId, memo);
        if (fee > 0) {
            ledgerService.debit(fromUserId, currency, fee, WalletLedgerType.FEE, "TRANSFER", t.getId(), null, null);
            recordFee(currency, fee);
        }
        ledgerService.credit(toUserId, currency, amount, WalletLedgerType.TRANSFER_IN,
            "TRANSFER", t.getId(), fromUserId, memo);
        limitService.addDaily(fromUserId, WalletLimitScene.TRANSFER, currency, amount + fee);
        t.setStatus(WalletTransferStatus.COMPLETED);
        transferRepository.save(t);
        rememberTransfer(t);
        return t;
    }

    private void rememberTransfer(WalletTransfer t) {
        cardReadCache.putTransfer(t);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    cardReadCache.evictTransfer(t);
                } else {
                    cardReadCache.putTransfer(t);
                }
            }
        });
    }

    private static void assertViewer(WalletTransfer t, String viewerUserId) {
        if (!isViewer(t, viewerUserId)) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND");
        }
    }

    private static boolean isViewer(WalletTransfer t, String viewerUserId) {
        return viewerUserId != null
            && (viewerUserId.equals(t.getFromUserId()) || viewerUserId.equals(t.getToUserId()));
    }

    private static java.util.Optional<WalletTransfer> firstInvolving(
        java.util.List<WalletTransfer> rows, String viewerUserId) {
        if (rows == null || rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        return rows.stream().filter(t -> isViewer(t, viewerUserId)).findFirst();
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

    private void recordFee(WalletCurrency currency, long fee) {
        WalletPlatformStats stats = statsRepository.findById(1L).orElseGet(() -> {
            WalletPlatformStats s = new WalletPlatformStats();
            s.setId(1L);
            return statsRepository.save(s);
        });
        if (currency == WalletCurrency.USDT) {
            stats.setTotalFeeUsdtMicro(stats.getTotalFeeUsdtMicro() + fee);
        } else {
            stats.setTotalFeePlatformFen(stats.getTotalFeePlatformFen() + fee);
        }
        statsRepository.save(stats);
    }
}
