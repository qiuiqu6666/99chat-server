package com.chat99.robotservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业务机器人微服务入口。
 *
 * <p>业务代码保持原包名 {@code com.chat99.server.robot}，便于与主仓 diff/回迁；
 * 数据源只有 jiqiren（由 {@code RobotSyncJdbcConfig} 手动装配），因此排除
 * Spring Boot 默认 DataSource 自动配置。
 */
@SpringBootApplication(
    scanBasePackages = {"com.chat99.robotservice", "com.chat99.server.robot"},
    exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
@EnableScheduling
public class RobotServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RobotServiceApplication.class, args);
    }
}
