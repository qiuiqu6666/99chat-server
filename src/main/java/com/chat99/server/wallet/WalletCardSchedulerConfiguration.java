package com.chat99.server.wallet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class WalletCardSchedulerConfiguration {
    /** Slow IM requests must not block expiry/refund and other application jobs. */
    @Bean(name = "walletCardScheduler")
    public ThreadPoolTaskScheduler walletCardScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("wallet-card-");
        return scheduler;
    }
}
