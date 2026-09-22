package com.chat99.server.wallet;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DepositScanExecutorConfig {

    @Bean(name = "depositScanExecutor")
    public Executor depositScanExecutor(WalletConfigService configService) {
        int n = Math.max(configService.getDepositScanConcurrency(), 1);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("deposit-scan-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
