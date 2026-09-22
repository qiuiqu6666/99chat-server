package com.chat99.server.adminapi;

import com.chat99.server.wallet.ExchangeRateService;
import com.chat99.server.wallet.WalletConfigService;
import com.chat99.server.wallet.WalletExchangeConfig;
import com.chat99.server.wallet.WalletExchangeConfigRepository;
import com.chat99.server.wallet.WalletPlatformStats;
import com.chat99.server.wallet.WalletPlatformStatsRepository;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminExchangeConfigService {

    private static final int MAX_MARKUP_BPS = 5_000;
    private static final int MAX_FLOAT_BPS = 2_000;
    private static final long MIN_WITHDRAW_USDT_MICRO = 1_000_000L;

    private final WalletExchangeConfigRepository exchangeConfigRepository;
    private final WalletConfigService walletConfigService;
    private final ExchangeRateService exchangeRateService;
    private final WalletPlatformStatsRepository statsRepository;
    private final AdminAuditService auditService;

    public AdminExchangeConfigService(WalletExchangeConfigRepository exchangeConfigRepository,
                                      WalletConfigService walletConfigService,
                                      ExchangeRateService exchangeRateService,
                                      WalletPlatformStatsRepository statsRepository,
                                      AdminAuditService auditService) {
        this.exchangeConfigRepository = exchangeConfigRepository;
        this.walletConfigService = walletConfigService;
        this.exchangeRateService = exchangeRateService;
        this.statsRepository = statsRepository;
        this.auditService = auditService;
    }

    public ExchangeConfigResponse get() {
        WalletExchangeConfig cfg = loadOrDefault();
        RatePreview ratePreview = buildRatePreview();
        PlatformStats stats = loadStats();
        return new ExchangeConfigResponse(
            cfg.isEnabled(),
            cfg.getMarkupBps(),
            cfg.getFloatBps(),
            cfg.getMinWithdrawUsdtMicro(),
            cfg.getUpdatedAt() == null ? null : cfg.getUpdatedAt().toString(),
            walletConfigService.getFrankfurterUrl(),
            walletConfigService.getExchangeRateCacheSeconds(),
            ratePreview,
            stats);
    }

    @Transactional
    public ExchangeConfigResponse update(HttpServletRequest http, Authentication auth, UpdateBody body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");

        WalletExchangeConfig cfg = loadOrDefault();
        if (body.enabled() != null) {
            cfg.setEnabled(body.enabled());
        }
        if (body.markupBps() != null) {
            int v = body.markupBps();
            if (v < 0 || v > MAX_MARKUP_BPS) {
                throw validationError("markup_bps out of range");
            }
            cfg.setMarkupBps(v);
        }
        if (body.floatBps() != null) {
            int v = body.floatBps();
            if (v < 0 || v > MAX_FLOAT_BPS) {
                throw validationError("float_bps out of range");
            }
            cfg.setFloatBps(v);
        }
        if (body.minWithdrawUsdtMicro() != null) {
            long v = body.minWithdrawUsdtMicro();
            if (v < MIN_WITHDRAW_USDT_MICRO) {
                throw validationError("min_withdraw_usdt_micro too small");
            }
            cfg.setMinWithdrawUsdtMicro(v);
        }
        cfg.setId(1L);
        exchangeConfigRepository.save(cfg);

        if (body.frankfurterUrl() != null) {
            String url = body.frankfurterUrl().trim();
            if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw validationError("invalid frankfurter_url");
            }
            walletConfigService.setFrankfurterUrl(url);
        }
        if (body.exchangeRateCacheSeconds() != null) {
            int seconds = body.exchangeRateCacheSeconds();
            if (seconds < 30 || seconds > 86_400) {
                throw validationError("exchange_rate_cache_seconds out of range");
            }
            walletConfigService.setExchangeRateCacheSeconds(seconds);
        }

        exchangeRateService.invalidateCache();
        auditService.log(http, admin.username(), "exchange_config.update", null,
            Map.of(
                "enabled", cfg.isEnabled(),
                "markupBps", cfg.getMarkupBps(),
                "floatBps", cfg.getFloatBps(),
                "minWithdrawUsdtMicro", cfg.getMinWithdrawUsdtMicro()));

        return get();
    }

    private WalletExchangeConfig loadOrDefault() {
        return exchangeConfigRepository.findById(1L).orElseGet(() -> {
            WalletExchangeConfig c = new WalletExchangeConfig();
            c.setId(1L);
            c.setEnabled(true);
            c.setMarkupBps(0);
            c.setFloatBps(50);
            c.setMinWithdrawUsdtMicro(MIN_WITHDRAW_USDT_MICRO);
            return c;
        });
    }

    private RatePreview buildRatePreview() {
        try {
            ExchangeRateService.UsdtPriceInfo price = exchangeRateService.usdtPriceInfo();
            ExchangeRateService.RateSnapshot snap = exchangeRateService.currentRate();
            long oneUsdtFen = exchangeRateService.usdtMicroToPlatformFen(1_000_000L, snap);
            return new RatePreview(
                round4(snap.usdCny()),
                round4(price.cnyPerUsdtBuy()),
                round4(price.cnyPerUsdtSell()),
                round4(price.cnyPerUsdt()),
                round2(oneUsdtFen / 100.0),
                price.updatedAt() == null ? null : price.updatedAt().toString(),
                null);
        } catch (Exception e) {
            return new RatePreview(null, null, null, null, null, null, e.getMessage());
        }
    }

    private PlatformStats loadStats() {
        WalletPlatformStats s = statsRepository.findById(1L).orElseGet(WalletPlatformStats::new);
        return new PlatformStats(
            s.getTotalExchangeSurplusFen(),
            round2(s.getTotalExchangeSurplusFen() / 100.0),
            s.getTotalFeeUsdtMicro(),
            s.getTotalFeePlatformFen());
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static AdminApiException validationError(String message) {
        return new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", message);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExchangeConfigResponse(
        boolean enabled,
        int markupBps,
        int floatBps,
        long minWithdrawUsdtMicro,
        String updatedAt,
        String frankfurterUrl,
        int exchangeRateCacheSeconds,
        RatePreview ratePreview,
        PlatformStats stats) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RatePreview(
        Double baseUsdCny,
        Double buyCnyPerUsdt,
        Double sellCnyPerUsdt,
        Double midCnyPerUsdt,
        Double exampleOneUsdtToPlatformYuan,
        String fetchedAt,
        String error) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlatformStats(
        long totalExchangeSurplusFen,
        double totalExchangeSurplusYuan,
        long totalFeeUsdtMicro,
        long totalFeePlatformFen) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateBody(
        Boolean enabled,
        Integer markupBps,
        Integer floatBps,
        Long minWithdrawUsdtMicro,
        String frankfurterUrl,
        Integer exchangeRateCacheSeconds) {}
}
