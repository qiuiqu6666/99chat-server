package com.chat99.server.im;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ImWebhookExecutorConfig {

    public static final String BEAN_NAME = "imWebhookExecutor";

    @Bean(name = BEAN_NAME)
    public ThreadPoolTaskExecutor imWebhookExecutor(ImWebhookDispatchProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.corePoolSize());
        executor.setMaxPoolSize(props.maxPoolSize());
        executor.setQueueCapacity(props.queueCapacity());
        executor.setThreadNamePrefix("im-webhook-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
