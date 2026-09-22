package com.chat99.server.group;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class GroupFanoutExecutorConfig {

    public static final String BEAN_NAME = "groupFanoutExecutor";

    @Bean(name = BEAN_NAME)
    public Executor groupFanoutExecutor(GroupFanoutProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.executorCorePoolSize());
        executor.setMaxPoolSize(props.executorMaxPoolSize());
        executor.setQueueCapacity(props.executorQueueCapacity());
        executor.setThreadNamePrefix("group-fanout-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
