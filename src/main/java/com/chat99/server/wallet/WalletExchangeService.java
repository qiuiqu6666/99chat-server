package com.chat99.server.wallet;

import com.chat99.server.notify.PlatformWalletNoticeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletExchangeService {

    private final ExchangeRateService rateService;
    private final WalletLedgerService ledgerService;
    private final WalletExchangeOrderRepository orderRepository;
    private final WalletExchangeConfigRepository exchangeConfigRepository;
    private final WalletPlatformStatsRepository statsRepository;
    private final PayPinService payPinService;
    private final WalletLimitService limitService;
    private final WalletFeeService feeService;
    private final PlatformWalletNoticeService platformWalletNotice;
    private final ObjectMapper json = new ObjectMapper();

    public WalletExchangeService(ExchangeRateService rateService, WalletLedgerService ledgerService,
                                 WalletExchangeOrderRepository orderRepository,
                                 WalletExchangeConfigRepository exchangeConfigRepository,
                                 WalletPlatformStatsRepository statsRepository,
                                 PayPinService payPinService, WalletLimitService limitService,
                                 WalletFeeService feeService,
                                 PlatformWalletNoticeService platformWalletNotice) {
        this.rateService = rateService;
        this.ledgerService = ledgerService;
        this.orderRepository = orderRepository;
        this.exchangeConfigRepository = exchangeConfigRepository;
        this.statsRepository = statsRepository;
        this.payPinService = payPinService;
        this.limitService = limitService;
        this.feeService = feeService;
        this.platformWalletNotice = platformWalletNotice;
    }

    @Transactional
    public WalletExchangeOrder exchange(String userId, ExchangeDirection direction, long inputAmount, String payPin) {
        requireExchangeEnabled();
        payPinService.requireSetAndVerify(userId, payPin);
        limitService.check(userId, WalletLimitScene.EXCHANGE,
            direction == ExchangeDirection.USDT_TO_PLATFORM ? WalletCurrency.USDT : WalletCurrency.PLATFORM,
            inputAmount);
        long fee = feeService.calculateFee(WalletFeeScene.EXCHANGE,
            direction == ExchangeDirection.USDT_TO_PLATFORM ? WalletCurrency.USDT : WalletCurrency.PLATFORM,
            inputAmount);
        ExchangeRateService.RateSnapshot rate = rateService.currentRate();
        long output;
        long surplusFen = 0;
        if (direction == ExchangeDirection.USDT_TO_PLATFORM) {
            long idealFen = (long) Math.floor(inputAmount / 1_000_000.0 * rate.usdCny() * 100.0
                * (1.0 + rate.markupBps() / 10_000.0));
            output = rateService.usdtMicroToPlatformFen(inputAmount, rate);
            surplusFen = Math.max(0, idealFen - output);
            if (output <= 0) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "EXCHANGE_AMOUNT_TOO_SMALL");
            }
        } else {
            output = rateService.platformFenToUsdtMicro(inputAmount, rate);
            surplusFen = 0;
            if (output <= 0) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "EXCHANGE_AMOUNT_TOO_SMALL");
            }
        }
        WalletExchangeOrder order = new WalletExchangeOrder();
        order.setUserId(userId);
        order.setDirection(direction);
        order.setInputAmount(inputAmount);
        order.setOutputAmount(output);
        order.setSurplusFen(surplusFen);
        try {
            order.setRateSnapshot(json.writeValueAsString(rate));
        } catch (JsonProcessingException ignored) {
            order.setRateSnapshot(rate.usdCny() + "");
        }
        orderRepository.save(order);
        long orderId = order.getId();
        if (direction == ExchangeDirection.USDT_TO_PLATFORM) {
            if (fee > 0) {
                ledgerService.debit(userId, WalletCurrency.USDT, fee, WalletLedgerType.FEE, "EXCHANGE", orderId, null, null);
            }
            ledgerService.debit(userId, WalletCurrency.USDT, inputAmount, WalletLedgerType.EXCHANGE_OUT,
                "EXCHANGE", orderId, null, null);
            ledgerService.credit(userId, WalletCurrency.PLATFORM, output, WalletLedgerType.EXCHANGE_IN,
                "EXCHANGE", orderId, null, null);
        } else {
            if (fee > 0) {
                ledgerService.debit(userId, WalletCurrency.PLATFORM, fee, WalletLedgerType.FEE, "EXCHANGE", orderId, null, null);
            }
            ledgerService.debit(userId, WalletCurrency.PLATFORM, inputAmount, WalletLedgerType.EXCHANGE_OUT,
                "EXCHANGE", orderId, null, null);
            ledgerService.credit(userId, WalletCurrency.USDT, output, WalletLedgerType.EXCHANGE_IN,
                "EXCHANGE", orderId, null, null);
        }
        if (surplusFen > 0) {
            recordSurplus(surplusFen);
        }
        limitService.addDaily(userId, WalletLimitScene.EXCHANGE,
            direction == ExchangeDirection.USDT_TO_PLATFORM ? WalletCurrency.USDT : WalletCurrency.PLATFORM,
            inputAmount);
        platformWalletNotice.notifyFlashExchange(order);
        return order;
    }

    private void requireExchangeEnabled() {
        WalletExchangeConfig cfg = exchangeConfigRepository.findById(1L).orElse(null);
        // 缺配置视为默认开放，与 migrate DEFAULT 1 / Bootstrap 一致
        if (cfg != null && !cfg.isEnabled()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_MAINTENANCE");
        }
    }

    private void recordSurplus(long surplusFen) {
        WalletPlatformStats stats = statsRepository.findById(1L).orElseGet(() -> {
            WalletPlatformStats s = new WalletPlatformStats();
            s.setId(1L);
            return statsRepository.save(s);
        });
        stats.setTotalExchangeSurplusFen(stats.getTotalExchangeSurplusFen() + surplusFen);
        statsRepository.save(stats);
    }
}
