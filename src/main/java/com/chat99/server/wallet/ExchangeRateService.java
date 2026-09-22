package com.chat99.server.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * USDT/CNY 汇率：内存缓存 + stale-while-revalidate + 异步单飞刷新。
 * 有过期快照时请求不阻塞 Frankfurter；冷启动无缓存时同步拉一次（短超时）。
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private final WalletConfigService configService;
    private final WalletExchangeConfigRepository exchangeConfigRepository;
    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<CachedRate> cache = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> inflightRefresh = new AtomicReference<>();
    private final Executor refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "exchange-rate-refresh");
        t.setDaemon(true);
        return t;
    });

    public ExchangeRateService(
            WalletConfigService configService,
            WalletExchangeConfigRepository exchangeConfigRepository,
            @Value("${chat99.wallet.exchange-rate-connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${chat99.wallet.exchange-rate-read-timeout-ms:3000}") long readTimeoutMs) {
        this.configService = configService;
        this.exchangeConfigRepository = exchangeConfigRepository;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(Math.max(200L, connectTimeoutMs), TimeUnit.MILLISECONDS)
            .readTimeout(Math.max(200L, readTimeoutMs), TimeUnit.MILLISECONDS)
            .build();
    }

    public record RateSnapshot(double usdCny, int markupBps, int floatBps, Instant fetchedAt) {}

    public RateSnapshot currentRate() {
        Instant now = Instant.now();
        CachedRate c = cache.get();
        if (c != null && c.expiresAt.isAfter(now)) {
            return c.snapshot;
        }
        if (c != null) {
            triggerAsyncRefresh();
            return c.snapshot;
        }
        synchronized (this) {
            c = cache.get();
            if (c != null) {
                if (!c.expiresAt.isAfter(Instant.now())) {
                    triggerAsyncRefresh();
                }
                return c.snapshot;
            }
            return doRefresh();
        }
    }

    /** platform fen per 1 USDT micro (6 decimals). */
    public long usdtMicroToPlatformFen(long usdtMicro, RateSnapshot rate) {
        double usd = usdtMicro / 1_000_000.0;
        double cny = usd * effectiveUsdCny(rate, true);
        return (long) Math.floor(cny * 100.0);
    }

    public long platformFenToUsdtMicro(long platformFen, RateSnapshot rate) {
        double cny = platformFen / 100.0;
        double usd = cny / effectiveUsdCny(rate, false);
        return (long) Math.floor(usd * 1_000_000.0);
    }

    /** 1 USDT 对人民币汇率（首页展示）。 */
    public record UsdtPriceInfo(
        double cnyPerUsdt,
        double cnyPerUsdtBuy,
        double cnyPerUsdtSell,
        Instant updatedAt) {}

    public UsdtPriceInfo usdtPriceInfo() {
        RateSnapshot rate = currentRate();
        double buy = effectiveUsdCny(rate, true);
        double sell = effectiveUsdCny(rate, false);
        double mid = (buy + sell) / 2.0;
        return new UsdtPriceInfo(mid, buy, sell, rate.fetchedAt());
    }

    /** 运营修改汇率参数后清缓存，并异步拉最新 Frankfurter + DB 配置。 */
    public void invalidateCache() {
        cache.set(null);
        triggerAsyncRefresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnReady() {
        triggerAsyncRefresh();
    }

    @Scheduled(fixedDelayString = "${chat99.wallet.exchange-rate-refresh-interval-ms:150000}")
    public void scheduledRefresh() {
        triggerAsyncRefresh();
    }

    private void triggerAsyncRefresh() {
        CompletableFuture<Void> existing = inflightRefresh.get();
        if (existing != null && !existing.isDone()) {
            return;
        }
        CompletableFuture<Void> created = new CompletableFuture<>();
        if (!inflightRefresh.compareAndSet(existing, created)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                doRefresh();
                created.complete(null);
            } catch (Exception e) {
                log.warn("exchange rate refresh failed: {}", e.getMessage());
                created.completeExceptionally(e);
            } finally {
                inflightRefresh.compareAndSet(created, null);
            }
        });
    }

    private RateSnapshot doRefresh() {
        double usdCny = fetchUsdCny();
        WalletExchangeConfig cfg = exchangeConfigRepository.findById(1L).orElse(defaultConfig());
        RateSnapshot snap = new RateSnapshot(usdCny, cfg.getMarkupBps(), cfg.getFloatBps(), Instant.now());
        long ttl = Math.max(1L, configService.getExchangeRateCacheSeconds());
        cache.set(new CachedRate(snap, Instant.now().plusSeconds(ttl)));
        return snap;
    }

    /** 测试用：写入指定过期时间的缓存快照。 */
    void seedCacheForTest(double usdCny, Instant expiresAt) {
        RateSnapshot snap = new RateSnapshot(usdCny, 0, 0, Instant.now());
        cache.set(new CachedRate(snap, expiresAt));
    }

    private double effectiveUsdCny(RateSnapshot rate, boolean usdtToPlatform) {
        double base = rate.usdCny() * (1.0 + rate.markupBps() / 10_000.0);
        double factor = rate.floatBps() / 10_000.0;
        if (usdtToPlatform) {
            return base * (1.0 - factor);
        }
        return base * (1.0 + factor);
    }

    /** 同包测试可覆盖。失败时若有缓存则回退其 usdCny。 */
    double fetchUsdCny() {
        Request req = new Request.Builder().url(configService.getFrankfurterUrl()).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return fallbackUsdCnyOrThrow();
            }
            JsonNode root = json.readTree(resp.body().string());
            double v = root.path("rates").path("CNY").asDouble(0);
            if (v <= 0) {
                return fallbackUsdCnyOrThrow();
            }
            return v;
        } catch (IOException e) {
            return fallbackUsdCnyOrThrow();
        }
    }

    private double fallbackUsdCnyOrThrow() {
        CachedRate c = cache.get();
        if (c != null) {
            return c.snapshot.usdCny();
        }
        throw WalletExceptions.of(HttpStatus.SERVICE_UNAVAILABLE, "RATE_UNAVAILABLE");
    }

    private WalletExchangeConfig defaultConfig() {
        WalletExchangeConfig c = new WalletExchangeConfig();
        c.setMarkupBps(0);
        c.setFloatBps(0);
        c.setMinWithdrawUsdtMicro(1_000_000L);
        return c;
    }

    private record CachedRate(RateSnapshot snapshot, Instant expiresAt) {}
}
