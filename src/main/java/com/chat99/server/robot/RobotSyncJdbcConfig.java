package com.chat99.server.robot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(prefix = "robot.sync.datasource", name = "url")
@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RobotSyncProperties.class)
public class RobotSyncJdbcConfig {

    @Bean
    public RobotSyncResources robotSyncResources(RobotSyncProperties props) {
        DataSource dataSource = buildDataSource(props.datasource());
        return new RobotSyncResources(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @Bean(name = "robotJdbc")
    public JdbcTemplate robotJdbc(@Qualifier("robotSyncResources") RobotSyncResources resources) {
        return new JdbcTemplate(resources.dataSource());
    }

    @Bean(name = "robotTransactionTemplate")
    public TransactionTemplate robotTransactionTemplate(@Qualifier("robotSyncResources") RobotSyncResources resources) {
        return new TransactionTemplate(resources.transactionManager());
    }

    private static DataSource buildDataSource(RobotSyncProperties.DatasourceConfig cfg) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(cfg.url());
        if (cfg.username() != null && !cfg.username().isBlank()) {
            config.setUsername(cfg.username());
        }
        if (cfg.password() != null && !cfg.password().isBlank()) {
            config.setPassword(cfg.password());
        }
        config.setMaximumPoolSize(cfg.maximumPoolSize());
        config.setPoolName("robot-sync");
        return new HikariDataSource(config);
    }

    public record RobotSyncResources(DataSource dataSource, DataSourceTransactionManager transactionManager) {}
}
