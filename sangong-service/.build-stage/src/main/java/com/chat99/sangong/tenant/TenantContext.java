package com.chat99.sangong.tenant;

/** 请求/Kafka 消费线程的当前租户（IM 游戏群 ID）。 */
public final class TenantContext {
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(tenantId.trim());
        }
    }

    public static String get() {
        return HOLDER.get();
    }

    public static String require() {
        String id = HOLDER.get();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("TENANT_REQUIRED");
        }
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static void run(String tenantId, Runnable action) {
        String prev = HOLDER.get();
        try {
            set(tenantId);
            action.run();
        } finally {
            if (prev == null) {
                clear();
            } else {
                set(prev);
            }
        }
    }
}
