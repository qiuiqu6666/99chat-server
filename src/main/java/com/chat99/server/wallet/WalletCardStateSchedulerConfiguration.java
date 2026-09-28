package com.chat99.server.wallet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class WalletCardStateSchedulerConfiguration {
    @Bean("walletCardStateScheduler")
    public ThreadPoolTaskScheduler walletCardStateScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("wallet-card-state-");
        return scheduler;
    }
}
