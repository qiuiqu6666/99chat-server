package com.chat99.server.chatattachment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatNativeVideoDurationMetrics {

    private static final Logger log = LoggerFactory.getLogger(ChatNativeVideoDurationMetrics.class);

    private final AtomicLong client = new AtomicLong();
    private final AtomicLong server = new AtomicLong();
    private final AtomicLong fallback = new AtomicLong();

    public void record(ChatMetadataProvenance source) {
        ChatMetadataProvenance src = source == null ? ChatMetadataProvenance.fallback : source;
        switch (src) {
            case client -> client.incrementAndGet();
            case server -> server.incrementAndGet();
            default -> fallback.incrementAndGet();
        }
        long c = client.get();
        long s = server.get();
        long f = fallback.get();
        long total = c + s + f;
        double ratio = total == 0L ? 0D : (double) f / (double) total;
        log.info("native_video_duration_source source={} client={} server={} fallback={} fallbackRatio={}",
            src.name(), c, s, f, String.format(java.util.Locale.ROOT, "%.4f", ratio));
        if (total >= 20 && ratio >= 0.05D) {
            log.warn("native_video_duration_source fallback ratio high fallback={} total={} ratio={}",
                f, total, String.format(java.util.Locale.ROOT, "%.4f", ratio));
        }
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("client", client.get());
        out.put("server", server.get());
        out.put("fallback", fallback.get());
        return out;
    }
}
