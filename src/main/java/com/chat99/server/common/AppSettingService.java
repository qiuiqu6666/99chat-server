package com.chat99.server.common;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingService {

    public static final String JWT_SECRET = "JWT_SECRET";
    public static final String IM_SDK_APP_ID = "IM_SDK_APP_ID";
    public static final String IM_KEY = "IM_KEY";
    public static final String SMSBAO_USER = "SMSBAO_USER";
    public static final String SMSBAO_PWD_MD5 = "SMSBAO_PWD_MD5";
    public static final String OSS_ENDPOINT = "OSS_ENDPOINT";
    public static final String OSS_BUCKET = "OSS_BUCKET";
    public static final String OSS_ACCESS_KEY_ID = "OSS_ACCESS_KEY_ID";
    public static final String OSS_ACCESS_KEY_SECRET = "OSS_ACCESS_KEY_SECRET";
    public static final String OSS_CDN_DOMAIN = "OSS_CDN_DOMAIN";

    private static final Logger log = LoggerFactory.getLogger(AppSettingService.class);

    private final AppSettingRepository repo;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public AppSettingService(AppSettingRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void load() {
        reload();
        log.info("AppSettingService loaded {} keys: {}", cache.size(), cache.keySet());
    }

    public synchronized void reload() {
        Map<String, String> fresh = new LinkedHashMap<>();
        repo.findAll().forEach(s -> fresh.put(s.getKey(), s.getValue()));
        cache.clear();
        cache.putAll(fresh);
    }

    public Optional<String> get(String key) {
        String v = cache.get(key);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }

    public String getRequired(String key) {
        return get(key).orElseThrow(() ->
            new IllegalStateException("Required app_setting missing: " + key));
    }

    public int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try { return Integer.parseInt(v); }
            catch (NumberFormatException e) {
                log.warn("app_setting {} is not an integer: {}", key, v);
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    @Transactional
    public void set(String key, String value) {
        AppSetting entity = repo.findById(key).orElseGet(() -> new AppSetting(key, value));
        entity.setValue(value);
        repo.save(entity);
        cache.put(key, value);
    }

    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(cache));
    }
}
