package com.chat99.sangong.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twemoji PNG 资源（与 PHP ImageTextHelper 的 emoji-cache 行为一致）：
 * 磁盘缓存目录 + 未命中时从 CDN 下载 72x72 PNG。
 */
public class EmojiAssets {
    private static final Logger log = LoggerFactory.getLogger(EmojiAssets.class);
    private static final String CDN_BASE = "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/";

    private final Path cacheDir;
    private final OkHttpClient http;
    private final ConcurrentHashMap<String, Boolean> missing = new ConcurrentHashMap<>();

    public EmojiAssets(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.warn("emoji cache dir create failed: {}", e.getMessage());
        }
    }

    public BufferedImage loadImage(String emoji) {
        for (String key : emojiKeys(emoji)) {
            byte[] data = ensureAsset(key);
            if (data != null) {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        return img;
                    }
                } catch (IOException ignored) {
                    // 尝试下一个 key
                }
            }
        }
        return null;
    }

    private byte[] ensureAsset(String key) {
        Path path = cacheDir.resolve(key + ".png");
        try {
            if (Files.isReadable(path)) {
                return Files.readAllBytes(path);
            }
        } catch (IOException ignored) {
            // 落到下载
        }
        if (missing.containsKey(key)) {
            return null;
        }
        byte[] data = download(key);
        if (data == null) {
            missing.put(key, Boolean.TRUE);
            return null;
        }
        try {
            Files.write(path, data);
        } catch (IOException e) {
            log.debug("emoji cache write failed key={} err={}", key, e.getMessage());
        }
        return data;
    }

    private byte[] download(String key) {
        Request request = new Request.Builder().url(CDN_BASE + key + ".png").build();
        try (Response response = http.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                byte[] data = response.body().bytes();
                return data.length > 0 ? data : null;
            }
        } catch (IOException e) {
            log.debug("emoji download failed key={} err={}", key, e.getMessage());
        }
        return null;
    }

    /** 与 PHP emojiKeys 一致：完整码点、去 FE0E、去 FE0E+FE0F 三种候选。 */
    private static List<String> emojiKeys(String emoji) {
        StringBuilder full = new StringBuilder();
        StringBuilder withoutTextSelectors = new StringBuilder();
        StringBuilder withoutAllSelectors = new StringBuilder();
        emoji.codePoints().forEach(cp -> {
            String hex = Integer.toHexString(cp);
            append(full, hex);
            if (cp != 0xFE0E) {
                append(withoutTextSelectors, hex);
            }
            if (cp != 0xFE0E && cp != 0xFE0F) {
                append(withoutAllSelectors, hex);
            }
        });
        Set<String> keys = new LinkedHashSet<>();
        for (StringBuilder sb : List.of(full, withoutTextSelectors, withoutAllSelectors)) {
            if (sb.length() > 0) {
                keys.add(sb.toString());
            }
        }
        return new ArrayList<>(keys);
    }

    private static void append(StringBuilder sb, String hex) {
        if (sb.length() > 0) {
            sb.append('-');
        }
        sb.append(hex);
    }
}
