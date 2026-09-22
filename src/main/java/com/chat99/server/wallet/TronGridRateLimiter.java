package com.chat99.server.wallet;

import org.springframework.stereotype.Component;

@Component
public class TronGridRateLimiter {

    private final WalletConfigService configService;
    private long nextSlotNanos;
    private long cooldownUntilNanos;

    public TronGridRateLimiter(WalletConfigService configService) {
        this.configService = configService;
    }

    public void acquire() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            long intervalNanos = 1_000_000_000L / Math.max(configService.getTrongridQpsLimit(), 1);
            long scheduled = Math.max(Math.max(now, nextSlotNanos), cooldownUntilNanos);
            nextSlotNanos = scheduled + intervalNanos;
            waitNanos = scheduled - now;
        }
        if (waitNanos > 0) {
            long millis = waitNanos / 1_000_000L;
            int nanos = (int) (waitNanos % 1_000_000L);
            try {
                Thread.sleep(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void coolDownMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        long until = System.nanoTime() + millis * 1_000_000L;
        cooldownUntilNanos = Math.max(cooldownUntilNanos, until);
    }
}
