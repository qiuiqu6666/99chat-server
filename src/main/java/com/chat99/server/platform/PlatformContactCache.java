package com.chat99.server.platform;

import com.chat99.server.platform.dto.ClientAppContext;
import com.chat99.server.platform.dto.PlatformContactResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * /api/v1/platform/contact 短 TTL 本地内存缓存,避免每秒数千次 DB 查询。
 *
 * <p>缓存粒度按 (platform, appVersion, appVersionCode, appChannel, deviceId) 五元组:
 * <ul>
 *   <li>同 deviceId 在 TTL 内复用结果,灰度命中稳定</li>
 *   <li>不同 deviceId 各自缓存一条,内存可控(MAX_SIZE 上限)</li>
 *   <li>升级/启动图发布后最多 TTL 秒全网生效</li>
 * </ul>
 *
 * <p>TTL 写死 5 秒;需要可调时再加 @ConfigurationProperties。
 */
@Component
public class PlatformContactCache {

    private static final Duration TTL = Duration.ofSeconds(5);
    /** 同一进程最多缓存 N 个 entry,防止 hash 冲突/极端 deviceId 数量爆内存。 */
    private static final int MAX_SIZE = 5000;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public PlatformContactResponse get(ClientAppContext ctx) {
        String key = buildKey(ctx);
        Entry e = store.get(key);
        if (e == null) return null;
        if (Instant.now().isAfter(e.expireAt)) {
            store.remove(key, e);
            return null;
        }
        return e.value;
    }

    public void put(ClientAppContext ctx, PlatformContactResponse value) {
        if (store.size() >= MAX_SIZE) {
            // 极冷门场景下短时抖动,但避免内存爆炸
            store.clear();
        }
        store.put(buildKey(ctx), new Entry(value, Instant.now().plus(TTL)));
    }

    public void invalidateAll() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    private static String buildKey(ClientAppContext ctx) {
        return (ctx.platform() == null ? "null" : ctx.platform().name())
            + "|" + (ctx.appVersion() == null ? "" : ctx.appVersion())
            + "|" + (ctx.appVersionCode() == null ? "" : ctx.appVersionCode())
            + "|" + (ctx.appChannel() == null ? "" : ctx.appChannel())
            + "|" + (ctx.deviceId() == null ? "" : ctx.deviceId());
    }

    private record Entry(PlatformContactResponse value, Instant expireAt) {}
}