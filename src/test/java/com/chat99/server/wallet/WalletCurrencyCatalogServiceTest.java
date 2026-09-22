package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletCurrencyCatalogServiceTest {

    @Mock WalletAccountService accountService;
    @Mock WalletWithdrawalRepository withdrawalRepository;
    @Mock ExchangeRateService exchangeRateService;

    private WalletCurrencyCatalogProperties catalogProps;
    private WalletCurrencyCatalogService service;

    @BeforeEach
    void setUp() {
        catalogProps = new WalletCurrencyCatalogProperties(List.of(
            new CurrencyCatalogItem("USDT", "USDT", "https://example.com/usdt.png",
                false, true, true, 1),
            new CurrencyCatalogItem("99", "99币", "https://example.com/99.png",
                true, false, false, 2)));
        WalletCurrencyCatalogEntryRepository entryRepository = org.mockito.Mockito.mock(
            WalletCurrencyCatalogEntryRepository.class);
        org.mockito.Mockito.when(entryRepository.count()).thenReturn(2L);
        org.mockito.Mockito.when(entryRepository.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(
            entry("USDT", "USDT", "https://example.com/usdt.png", false, true, true, 1),
            entry("99", "99币", "https://example.com/99.png", true, false, false, 2)));
        WalletCurrencyCatalogConfigService configService = new WalletCurrencyCatalogConfigService(
            catalogProps, entryRepository);
        service = new WalletCurrencyCatalogService(
            configService, accountService, withdrawalRepository, exchangeRateService);
    }

    private static WalletCurrencyCatalogEntry entry(String code, String name, String logoUrl,
                                                    boolean platformCoin, boolean depositEnabled,
                                                    boolean withdrawEnabled, int sortOrder) {
        WalletCurrencyCatalogEntry e = new WalletCurrencyCatalogEntry();
        e.setCode(code);
        e.setName(name);
        e.setLogoUrl(logoUrl);
        e.setPlatformCoin(platformCoin);
        e.setDepositEnabled(depositEnabled);
        e.setWithdrawEnabled(withdrawEnabled);
        e.setSortOrder(sortOrder);
        e.setEnabled(true);
        return e;
    }

    @Test
    void buildForUser_returnsBalancesPricesAndFlags() {
        UserWallet wallet = new UserWallet();
        wallet.setUserId("user1");
        wallet.setBalanceUsdtMicro(2_500_000L);
        wallet.setBalancePlatformFen(128_800L);
        when(accountService.ensureWalletByUserId("user1")).thenReturn(wallet);
        when(withdrawalRepository.sumPendingAmountMicro(eq("user1"), any()))
            .thenReturn(500_000L);
        Instant updatedAt = Instant.parse("2026-06-07T10:00:00Z");
        when(exchangeRateService.usdtPriceInfo())
            .thenReturn(new ExchangeRateService.UsdtPriceInfo(7.2456, 7.2, 7.28, updatedAt));

        WalletCurrencyCatalogService.WalletCurrenciesResponse res = service.buildForUser("user1");

        assertThat(res.priceUpdatedAt()).isEqualTo(updatedAt);
        assertThat(res.currencies()).hasSize(2);

        WalletCurrencyCatalogService.CurrencyItemDto usdt = res.currencies().get(0);
        assertThat(usdt.code()).isEqualTo("USDT");
        assertThat(usdt.name()).isEqualTo("USDT");
        assertThat(usdt.logoUrl()).isEqualTo("https://example.com/usdt.png");
        assertThat(usdt.price()).isEqualTo(7.2456);
        assertThat(usdt.amount()).isEqualTo(2_500_000L);
        assertThat(usdt.amountDisplay()).isEqualTo("2.500000");
        assertThat(usdt.availableAmount()).isEqualTo(2_000_000L);
        assertThat(usdt.frozenAmount()).isEqualTo(500_000L);
        assertThat(usdt.depositEnabled()).isTrue();
        assertThat(usdt.withdrawEnabled()).isTrue();
        assertThat(usdt.platformCoin()).isFalse();

        WalletCurrencyCatalogService.CurrencyItemDto platform = res.currencies().get(1);
        assertThat(platform.code()).isEqualTo("99");
        assertThat(platform.name()).isEqualTo("99币");
        assertThat(platform.price()).isEqualTo(1.0);
        assertThat(platform.amount()).isEqualTo(128_800L);
        assertThat(platform.amountDisplay()).isEqualTo("1288.00");
        assertThat(platform.depositEnabled()).isFalse();
        assertThat(platform.withdrawEnabled()).isFalse();
        assertThat(platform.platformCoin()).isTrue();
    }

    @Test
    void buildForUser_usdtPriceNullWhenExchangeUnavailable() {
        UserWallet wallet = new UserWallet();
        wallet.setUserId("user1");
        wallet.setBalanceUsdtMicro(0L);
        wallet.setBalancePlatformFen(0L);
        when(accountService.ensureWalletByUserId("user1")).thenReturn(wallet);
        when(withdrawalRepository.sumPendingAmountMicro(eq("user1"), any())).thenReturn(0L);
        when(exchangeRateService.usdtPriceInfo()).thenThrow(new RuntimeException("rate down"));

        WalletCurrencyCatalogService.WalletCurrenciesResponse res = service.buildForUser("user1");

        assertThat(res.priceUpdatedAt()).isNull();
        assertThat(res.currencies().get(0).price()).isNull();
        assertThat(res.currencies().get(1).price()).isEqualTo(1.0);
    }
}
