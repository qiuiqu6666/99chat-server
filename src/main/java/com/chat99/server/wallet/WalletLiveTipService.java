package com.chat99.server.wallet;

import com.chat99.server.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletLiveTipService {

    private final UserRepository userRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final WalletLedgerService ledgerService;
    private final PayPinService payPinService;
    private final WalletLimitService limitService;
    private final WalletFeeService feeService;
    private final WalletPlatformStatsRepository statsRepository;

    public WalletLiveTipService(
        UserRepository userRepository,
        WalletLedgerRepository ledgerRepository,
        WalletLedgerService ledgerService,
        PayPinService payPinService,
        WalletLimitService limitService,
        WalletFeeService feeService,
        WalletPlatformStatsRepository statsRepository
    ) {
        this.userRepository = userRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService = ledgerService;
        this.payPinService = payPinService;
        this.limitService = limitService;
        this.feeService = feeService;
        this.statsRepository = statsRepository;
    }

    @Transactional
    public Map<String, Object> tip(
        String fromUserId,
        String toUserId,
        WalletCurrency currency,
        long amount,
        String payPin,
        String clientOrderId,
        Long liveTipOrderId
    ) {
        if (fromUserId == null || fromUserId.isBlank() || toUserId == null || toUserId.isBlank()) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (currency == null || amount <= 0) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
        }
        if (fromUserId.equals(toUserId)) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "CANNOT_TIP_SELF");
        }
        if (!userRepository.existsByUserId(toUserId)) {
            throw WalletExceptions.of(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
        }
        String cid = clientOrderId.trim();
        if (cid.length() > 64) {
            throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }

        var existing = ledgerRepository.findFirstByUserIdAndLedgerTypeAndRemarkOrderByIdAsc(
            fromUserId, WalletLedgerType.LIVE_TIP_OUT, cid);
        if (existing.isPresent()) {
            return replay(existing.get(), toUserId, currency, amount, liveTipOrderId);
        }

        payPinService.requireSetAndVerify(fromUserId, payPin);
        limitService.check(fromUserId, WalletLimitScene.LIVE_TIP, currency, amount);
        long fee = feeService.calculateFee(WalletFeeScene.LIVE_TIP, currency, amount);

        ledgerService.debit(fromUserId, currency, amount, WalletLedgerType.LIVE_TIP_OUT,
            "LIVE_TIP", liveTipOrderId, toUserId, cid);
        WalletLedger out = ledgerRepository.findFirstByUserIdAndLedgerTypeAndRemarkOrderByIdAsc(
            fromUserId, WalletLedgerType.LIVE_TIP_OUT, cid)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.INTERNAL_SERVER_ERROR, "LEDGER_WRITE_FAILED"));
        if (fee > 0) {
            ledgerService.debit(fromUserId, currency, fee, WalletLedgerType.FEE, "LIVE_TIP", liveTipOrderId, null, null);
            recordFee(currency, fee);
        }
        ledgerService.credit(toUserId, currency, amount, WalletLedgerType.LIVE_TIP_IN,
            "LIVE_TIP", out.getId(), fromUserId, cid);
        WalletLedger in = ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc("LIVE_TIP", out.getId())
            .stream()
            .filter(l -> l.getLedgerType() == WalletLedgerType.LIVE_TIP_IN)
            .findFirst()
            .orElse(null);
        limitService.addDaily(fromUserId, WalletLimitScene.LIVE_TIP, currency, amount + fee);
        return result(liveTipOrderId, out.getId(), in == null ? null : in.getId(), fee);
    }

    private Map<String, Object> replay(
        WalletLedger out,
        String toUserId,
        WalletCurrency currency,
        long amount,
        Long liveTipOrderId
    ) {
        if (out.getCurrency() != currency
            || Math.abs(out.getAmount()) != amount
            || !toUserId.equals(out.getCounterpartUserId())) {
            throw WalletExceptions.of(HttpStatus.CONFLICT, "CLIENT_ORDER_ID_CONFLICT");
        }
        Long inId = ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc("LIVE_TIP", out.getId())
            .stream()
            .filter(l -> l.getLedgerType() == WalletLedgerType.LIVE_TIP_IN)
            .map(WalletLedger::getId)
            .findFirst()
            .orElse(null);
        long fee = 0;
        if (liveTipOrderId != null) {
            fee = ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc("LIVE_TIP", liveTipOrderId)
                .stream()
                .filter(l -> l.getLedgerType() == WalletLedgerType.FEE)
                .map(l -> Math.abs(l.getAmount()))
                .findFirst()
                .orElse(0L);
        }
        return result(liveTipOrderId, out.getId(), inId, fee);
    }

    private static Map<String, Object> result(Long tipId, Long ledgerOutId, Long ledgerInId, long feeAmount) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (tipId != null) {
            out.put("tipId", tipId);
        }
        out.put("ledgerOutId", ledgerOutId);
        out.put("ledgerInId", ledgerInId);
        out.put("feeAmount", feeAmount);
        return out;
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
