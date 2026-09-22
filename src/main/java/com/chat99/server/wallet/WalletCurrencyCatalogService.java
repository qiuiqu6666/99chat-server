package com.chat99.server.wallet;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WalletCurrencyCatalogService {

    private final WalletCurrencyCatalogConfigService catalogConfigService;
    private final WalletAccountService accountService;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final ExchangeRateService exchangeRateService;

    public WalletCurrencyCatalogService(WalletCurrencyCatalogConfigService catalogConfigService,
                                        WalletAccountService accountService,
                                        WalletWithdrawalRepository withdrawalRepository,
                                        ExchangeRateService exchangeRateService) {
        this.catalogConfigService = catalogConfigService;
        this.accountService = accountService;
        this.withdrawalRepository = withdrawalRepository;
        this.exchangeRateService = exchangeRateService;
    }

    public record CurrencyItemDto(
        String code,
        String name,
        String logoUrl,
        Double price,
        String priceCurrency,
        long amount,
        String amountUnit,
        int decimals,
        String amountDisplay,
        long availableAmount,
        long frozenAmount,
        boolean depositEnabled,
        boolean withdrawEnabled,
        boolean platformCoin,
        int sortOrder) {}

    public record WalletCurrenciesResponse(
        List<CurrencyItemDto> currencies,
        Instant priceUpdatedAt) {}

    public WalletCurrenciesResponse buildForUser(String userId) {
        UserWallet wallet;
        try {
            wallet = accountService.ensureWalletByUserId(userId);
        } catch (Exception e) {
            throw new RuntimeException("WALLET_NOT_CONFIGURED");
        }
        if (wallet == null) {
            throw new RuntimeException("WALLET_NOT_CONFIGURED");
        }
        long frozenUsdtMicro = withdrawalRepository.sumPendingAmountMicro(
            userId, List.of(WithdrawalStatus.PENDING));

        Double usdtCnyPrice = null;
        Instant priceUpdatedAt = null;
        try {
            ExchangeRateService.UsdtPriceInfo priceInfo = exchangeRateService.usdtPriceInfo();
            usdtCnyPrice = roundPrice(priceInfo.cnyPerUsdt());
            priceUpdatedAt = priceInfo.updatedAt();
        } catch (Exception ignored) {
            // 汇率不可用时 USDT price 为 null
        }

        final Double usdtPrice = usdtCnyPrice;
        List<CurrencyItemDto> currencies = catalogConfigService.listEnabledItems().stream()
            .map(item -> toDto(item, wallet, frozenUsdtMicro, usdtPrice))
            .toList();
        return new WalletCurrenciesResponse(currencies, priceUpdatedAt);
    }

    private static CurrencyItemDto toDto(CurrencyCatalogItem item, UserWallet wallet,
                                         long frozenUsdtMicro, Double usdtPrice) {
        BalanceSnapshot balance = readBalance(item.code(), wallet, frozenUsdtMicro);
        Double price = resolvePrice(item.code(), usdtPrice);
        return new CurrencyItemDto(
            item.code(),
            item.name(),
            item.logoUrl(),
            price,
            "CNY",
            balance.amount(),
            balance.amountUnit(),
            balance.decimals(),
            balance.amountDisplay(),
            balance.availableAmount(),
            balance.frozenAmount(),
            item.depositEnabled(),
            item.withdrawEnabled(),
            item.platformCoin(),
            item.sortOrder());
    }

    private static Double resolvePrice(String code, Double usdtPrice) {
        if ("99".equalsIgnoreCase(code) || "PLATFORM".equalsIgnoreCase(code)) {
            return 1.0;
        }
        if ("USDT".equalsIgnoreCase(code)) {
            return usdtPrice;
        }
        return null;
    }

    private record BalanceSnapshot(
        long amount,
        String amountUnit,
        int decimals,
        String amountDisplay,
        long availableAmount,
        long frozenAmount) {}

    private static BalanceSnapshot readBalance(String code, UserWallet wallet, long frozenUsdtMicro) {
        if ("USDT".equalsIgnoreCase(code)) {
            long amount = wallet.getBalanceUsdtMicro();
            long frozen = Math.min(frozenUsdtMicro, amount);
            long available = Math.max(0, amount - frozen);
            return new BalanceSnapshot(
                amount, "micro", 6,
                WalletDisplayFormats.fromMicro(amount),
                available, frozen);
        }
        if ("99".equalsIgnoreCase(code) || "PLATFORM".equalsIgnoreCase(code)) {
            long amount = wallet.getBalancePlatformFen();
            return new BalanceSnapshot(
                amount, "fen", 2,
                WalletDisplayFormats.fromFen(amount),
                amount, 0L);
        }
        return new BalanceSnapshot(0L, "micro", 6, "0.000000", 0L, 0L);
    }

    private static double roundPrice(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
