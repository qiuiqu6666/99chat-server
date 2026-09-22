package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.PayMethod;
import com.chat99.server.wallet.ExchangeRateService;
import com.chat99.server.wallet.PayPinService;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletExceptions;
import com.chat99.server.wallet.WalletLedger;
import com.chat99.server.wallet.WalletLedgerRepository;
import com.chat99.server.wallet.WalletLedgerService;
import com.chat99.server.wallet.WalletLedgerType;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LifePaymentPaymentService {

    private final PayPinService payPinService;
    private final WalletLedgerService ledgerService;
    private final WalletLedgerRepository ledgerRepository;
    private final ExchangeRateService exchangeRateService;

    public LifePaymentPaymentService(PayPinService payPinService,
                                     WalletLedgerService ledgerService,
                                     WalletLedgerRepository ledgerRepository,
                                     ExchangeRateService exchangeRateService) {
        this.payPinService = payPinService;
        this.ledgerService = ledgerService;
        this.ledgerRepository = ledgerRepository;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional
    public void charge(String userId, PayMethod payMethod, BigDecimal amountYuan,
                       String payPassword, Long orderId, String remark) {
        if (payPassword == null || payPassword.isBlank()) {
            throw LifePaymentExceptions.badRequest("pay_password_error");
        }
        try {
            payPinService.requireSetAndVerify(userId, payPassword);
        } catch (ResponseStatusException e) {
            String reason = e.getReason() == null ? "" : e.getReason();
            if ("PAY_PIN_INVALID".equals(reason) || "INVALID_PAY_PIN".equals(reason)
                || "PAY_PIN_NOT_SET".equals(reason) || "PAY_PIN_LOCKED".equals(reason)) {
                throw LifePaymentExceptions.forbidden("pay_password_error");
            }
            throw e;
        }

        try {
            if (payMethod == PayMethod.coin_99) {
                long fen = LifePaymentSupport.yuanToFen(amountYuan);
                ledgerService.debit(userId, WalletCurrency.PLATFORM, fen, WalletLedgerType.LIFE_PAYMENT,
                    "LIFE_PAYMENT", orderId, null, remark);
            } else {
                ExchangeRateService.RateSnapshot rate = exchangeRateService.currentRate();
                long fen = LifePaymentSupport.yuanToFen(amountYuan);
                long usdtMicro = exchangeRateService.platformFenToUsdtMicro(fen, rate);
                if (usdtMicro <= 0) {
                    throw LifePaymentExceptions.badRequest("invalid_amount");
                }
                ledgerService.debit(userId, WalletCurrency.USDT, usdtMicro, WalletLedgerType.LIFE_PAYMENT,
                    "LIFE_PAYMENT", orderId, null, remark);
            }
        } catch (ResponseStatusException e) {
            if ("INSUFFICIENT_BALANCE".equals(e.getReason())) {
                throw WalletExceptions.of(HttpStatus.CONFLICT, "insufficient_platform_balance");
            }
            throw e;
        }
    }

    /** 按原扣款流水原路退回；已退过则幂等跳过。 */
    @Transactional
    public void refundIfNeeded(String userId, Long orderId, String remark) {
        List<WalletLedger> ledgers = ledgerRepository.findByRefTypeAndRefIdOrderByCreatedAtAsc(
            "LIFE_PAYMENT", orderId);
        long debitSum = 0;
        long creditSum = 0;
        WalletCurrency currency = null;
        for (WalletLedger ledger : ledgers) {
            if (ledger.getAmount() < 0) {
                debitSum += -ledger.getAmount();
                currency = ledger.getCurrency();
            } else if (ledger.getAmount() > 0) {
                creditSum += ledger.getAmount();
            }
        }
        long remain = debitSum - creditSum;
        if (remain <= 0 || currency == null) {
            return;
        }
        ledgerService.credit(userId, currency, remain, WalletLedgerType.LIFE_PAYMENT,
            "LIFE_PAYMENT_REFUND", orderId, null, remark);
    }
}
