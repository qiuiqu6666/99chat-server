package com.chat99.server.adminapi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/**
 * 进程内 API 请求指标：按路径聚合 + 全量请求明细环形缓冲。
 * 重启清空；多实例各自独立（运维页只看当前连上的这台）。
 */
@Component
public class ApiRequestMetricsStore {

    public static final long DEFAULT_SLOW_MS = 800L;
    private static final int MAX_PATHS = 800;
    private static final int MAX_RECENT_ALL = 10_000;

    private final ConcurrentHashMap<String, PathStats> pathStats = new ConcurrentHashMap<>();
    private final Object allLock = new Object();
    private final ArrayList<Sample> recentAll = new ArrayList<>();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong startedAtMs = new AtomicLong(System.currentTimeMillis());
    private volatile long slowThresholdMs = DEFAULT_SLOW_MS;

    public void setSlowThresholdMs(long ms) {
        if (ms >= 50L && ms <= 60_000L) {
            this.slowThresholdMs = ms;
        }
    }

    public long slowThresholdMs() {
        return slowThresholdMs;
    }

    public void record(String method,
                       String path,
                       int status,
                       long durationMs,
                       String errorHint,
                       String ip,
                       String userAgent,
                       String platform,
                       String deviceModel) {
        if (path == null || path.isBlank()) {
            return;
        }
        String m = method == null ? "?" : method.toUpperCase();
        String p = truncate(path, 200);
        String key = m + " " + p;
        totalRequests.incrementAndGet();
        boolean isError = status >= 400;
        if (isError) {
            totalErrors.incrementAndGet();
        }

        PathStats stats = pathStats.get(key);
        if (stats == null && pathStats.size() < MAX_PATHS) {
            PathStats created = new PathStats(m, p);
            PathStats raced = pathStats.putIfAbsent(key, created);
            stats = raced != null ? raced : created;
        }
        if (stats != null) {
            stats.accept(status, durationMs);
        }

        long now = System.currentTimeMillis();
        Sample sample = new Sample(
            now,
            m,
            p,
            status,
            durationMs,
            truncate(errorHint, 240),
            emptyIfNull(truncate(ip, 64)),
            emptyIfNull(truncate(userAgent, 255)),
            emptyIfNull(truncate(platform, 16)),
            emptyIfNull(truncate(deviceModel, 64)));
        synchronized (allLock) {
            recentAll.add(sample);
            int overflow = recentAll.size() - MAX_RECENT_ALL;
            if (overflow > 0) {
                recentAll.subList(0, overflow).clear();
            }
        }
    }

    public void reset() {
        pathStats.clear();
        synchronized (allLock) {
            recentAll.clear();
        }
        totalRequests.set(0);
        totalErrors.set(0);
        startedAtMs.set(System.currentTimeMillis());
    }

    public Map<String, Object> snapshot(int pathLimit, int slowLimit, int errorLimit) {
        int paths = clamp(pathLimit, 20, 500);
        // slowLimit / errorLimit retained for API compatibility; no longer used
        clamp(slowLimit, 20, 300);
        clamp(errorLimit, 20, 300);

        List<Map<String, Object>> pathRows = new ArrayList<>();
        for (PathStats s : pathStats.values()) {
            pathRows.add(s.toMap());
        }
        pathRows.sort(
            Comparator
                .comparingLong((Map<String, Object> r) -> ((Number) r.get("error_count")).longValue())
                .reversed()
                .thenComparing(
                    Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("max_ms")).longValue())
                        .reversed())
                .thenComparing(
                    Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("count")).longValue())
                        .reversed()));
        if (pathRows.size() > paths) {
            pathRows = pathRows.subList(0, paths);
        }

        int bufferSize;
        synchronized (allLock) {
            bufferSize = recentAll.size();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started_at", startedAtMs.get());
        out.put("collected_at", System.currentTimeMillis());
        out.put("slow_threshold_ms", slowThresholdMs);
        out.put("total_requests", totalRequests.get());
        out.put("total_errors", totalErrors.get());
        out.put("path_count", pathStats.size());
        out.put("buffer_size", bufferSize);
        out.put("buffer_capacity", MAX_RECENT_ALL);
        out.put("paths", new ArrayList<>(pathRows));
        writeLatestSnapshot(out);
        return out;
    }

    public Map<String, Object> queryRequests(
            int page,
            int pageSize,
            String method,
            String path,
            Integer status,
            Integer statusGte,
            String ip,
            String device,
            String keyword,
            boolean slowOnly,
            Long fromMs,
            Long toMs) {
        int p = Math.max(1, page);
        int size = clamp(pageSize, 10, 200);
        String methodFilter = blankToNull(method);
        if (methodFilter != null) {
            methodFilter = methodFilter.toUpperCase(Locale.ROOT);
        }
        String pathFilter = lowerOrNull(path);
        String ipFilter = lowerOrNull(ip);
        String deviceFilter = lowerOrNull(device);
        String keywordFilter = lowerOrNull(keyword);
        long slowMs = slowThresholdMs;

        List<Sample> matched = new ArrayList<>();
        int bufferSize;
        synchronized (allLock) {
            bufferSize = recentAll.size();
            for (int i = recentAll.size() - 1; i >= 0; i--) {
                Sample s = recentAll.get(i);
                if (methodFilter != null && !methodFilter.equals(s.method())) {
                    continue;
                }
                if (pathFilter != null && !containsIgnoreCase(s.path(), pathFilter)) {
                    continue;
                }
                if (status != null) {
                    if (s.status() != status) {
                        continue;
                    }
                } else if (statusGte != null && s.status() < statusGte) {
                    continue;
                }
                if (ipFilter != null && !containsIgnoreCase(s.ip(), ipFilter)) {
                    continue;
                }
                if (deviceFilter != null) {
                    String blob = (s.userAgent() + " " + s.platform() + " " + s.deviceModel()).toLowerCase(Locale.ROOT);
                    if (!blob.contains(deviceFilter)) {
                        continue;
                    }
                }
                if (keywordFilter != null) {
                    boolean hitPath = containsIgnoreCase(s.path(), keywordFilter);
                    boolean hitError = containsIgnoreCase(s.error() == null ? "" : s.error(), keywordFilter);
                    if (!hitPath && !hitError) {
                        continue;
                    }
                }
                if (slowOnly && s.durationMs() < slowMs) {
                    continue;
                }
                if (fromMs != null && s.atMs() < fromMs) {
                    continue;
                }
                if (toMs != null && s.atMs() > toMs) {
                    continue;
                }
                matched.add(s);
            }
        }

        int total = matched.size();
        int from = Math.min((p - 1) * size, total);
        int to = Math.min(from + size, total);
        List<Map<String, Object>> items = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            items.add(matched.get(i).toMap());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("page_size", size);
        out.put("slow_threshold_ms", slowMs);
        out.put("buffer_size", bufferSize);
        out.put("buffer_capacity", MAX_RECENT_ALL);
        out.put("items", items);
        return out;
    }

    private static void writeLatestSnapshot(Map<String, Object> out) {
        try {
            Path dir = Path.of("/www/wwwroot/99chat-server/logs");
            Files.createDirectories(dir);
            Path target = dir.resolve("api-metrics-latest.json");
            Path tmp = dir.resolve("api-metrics-latest.json.tmp");
            StringBuilder sb = new StringBuilder(4096);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : out.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append('"').append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            // 落盘失败不影响主流程
        }
    }

    private static void appendJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append('"').append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendJson(sb, list.get(i));
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        if (haystack == null || needleLower == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String lowerOrNull(String s) {
        String t = blankToNull(s);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max);
    }

    public record Sample(
            long atMs,
            String method,
            String path,
            int status,
            long durationMs,
            String error,
            String ip,
            String userAgent,
            String platform,
            String deviceModel) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", atMs);
            m.put("method", method);
            m.put("path", path);
            m.put("status", status);
            m.put("duration_ms", durationMs);
            m.put("ip", ip == null ? "" : ip);
            m.put("user_agent", userAgent == null ? "" : userAgent);
            m.put("platform", platform == null ? "" : platform);
            m.put("device_model", deviceModel == null ? "" : deviceModel);
            m.put("error", error == null ? "" : error);
            return m;
        }
    }

    static final class PathStats {
        private final String method;
        private final String path;
        private final LongAdder count = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLong lastMs = new AtomicLong();
        private final AtomicLong lastAt = new AtomicLong();
        private final AtomicLong lastStatus = new AtomicLong();

        PathStats(String method, String path) {
            this.method = method;
            this.path = path;
        }

        void accept(int status, long durationMs) {
            count.increment();
            totalMs.add(Math.max(0L, durationMs));
            lastMs.set(durationMs);
            lastAt.set(System.currentTimeMillis());
            lastStatus.set(status);
            if (status >= 400) {
                errorCount.increment();
            }
            maxMs.accumulateAndGet(durationMs, Math::max);
        }

        Map<String, Object> toMap() {
            long c = count.sum();
            long total = totalMs.sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", method);
            m.put("path", path);
            m.put("count", c);
            m.put("error_count", errorCount.sum());
            m.put("avg_ms", c == 0 ? 0L : total / c);
            m.put("max_ms", maxMs.get());
            m.put("last_ms", lastMs.get());
            m.put("last_status", lastStatus.get());
            m.put("last_at", lastAt.get());
            return m;
        }
    }
}
