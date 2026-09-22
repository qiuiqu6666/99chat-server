package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.tenant.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 实时事件版本号（按租户分文件）。 */
@Service
public class RealtimeVersionStore {
    private static final Logger log = LoggerFactory.getLogger(RealtimeVersionStore.class);
    private final Path baseDir;

    public RealtimeVersionStore(SangongProperties props) {
        this.baseDir = Path.of(props.getStorageDir(), "realtime");
    }

    public synchronized void touch() {
        // 显式要求 TenantContext：ctx 缺失时不能再 fall back 到全局文件（会让两个群的 SSE
        // 客户端互相看到对方的变化）。这里 throw 让上游响亮失败。
        String tenant = TenantContext.require();
        try {
            Path path = versionPathFor(tenant);
            Files.createDirectories(path.getParent());
            long version = getVersionFor(tenant) + 1;
            Files.writeString(path, String.valueOf(version), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("realtime version touch failed: {}", e.getMessage());
        }
    }

    public long getVersion() {
        // 兼容旧调用方：ctx 不存在时 throw（避免无声 fallback 到全局）。
        return getVersionFor(TenantContext.require());
    }

    private long getVersionFor(String tenant) {
        try {
            Path path = versionPathFor(tenant);
            if (!Files.isRegularFile(path)) {
                return 0;
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? 0 : Long.parseLong(raw);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private Path versionPath() {
        // 内部默认拿 ctx（getVersion 已被要求 require；touch 也已 require）。统一走 versionPathFor。
        return versionPathFor(TenantContext.require());
    }

    private Path versionPathFor(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            // 防御：理论上不会到这里（getVersion/touch 已 require），万一走到也 throw。
            throw new IllegalStateException("RealtimeVersionStore requires non-empty tenantId");
        }
        String safe = tenant.replaceAll("[^A-Za-z0-9._@#-]", "_");
        return baseDir.resolve(safe).resolve("version");
    }
}
