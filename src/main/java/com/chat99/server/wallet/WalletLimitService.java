package com.chat99.server.wallet;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class WalletLimitService {

    private final WalletLimitConfigRepository limitRepository;
    private final StringRedisTemplate redis;

    public WalletLimitService(WalletLimitConfigRepository limitRepository, StringRedisTemplate redis) {
        this.limitRepository = limitRepository;
        this.redis = redis;
    }

    public void check(String userId, WalletLimitScene scene, WalletCurrency currency, long amount) {
        WalletLimitConfig cfg = limitRepository.findBySceneAndCurrency(scene, currency).orElse(null);
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        if (amount > cfg.getPerTxMax()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "LIMIT_EXCEEDED");
        }
        String key = dailyKey(userId, scene, currency);
        long used = parseLong(redis.opsForValue().get(key));
        if (used + amount > cfg.getDailyMax()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "LIMIT_EXCEEDED");
        }
    }

    public void addDaily(String userId, WalletLimitScene scene, WalletCurrency currency, long amount) {
        WalletLimitConfig cfg = limitRepository.findBySceneAndCurrency(scene, currency).orElse(null);
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        String key = dailyKey(userId, scene, currency);
        Long v = redis.opsForValue().increment(key, amount);
        if (v != null && v == amount) {
            redis.expire(key, java.time.Duration.ofDays(2));
        }
    }

    private static String dailyKey(String userId, WalletLimitScene scene, WalletCurrency currency) {
        String day = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString();
        return "wallet:daily:" + userId + ":" + scene + ":" + currency + ":" + day;
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
