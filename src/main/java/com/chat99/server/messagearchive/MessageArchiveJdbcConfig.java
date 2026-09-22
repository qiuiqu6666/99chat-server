package com.chat99.server.messagearchive;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class MessageArchiveJdbcConfig {

    @Bean(name = "archiveWriteJdbc")
    public JdbcTemplate archiveWriteJdbc(MessageArchiveProperties props, DataSource dataSource) {
        if (props.writeDatasource().configured()) {
            return new JdbcTemplate(buildDedicated(props.writeDatasource(), "archive-write"));
        }
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "archiveReadJdbc")
    public JdbcTemplate archiveReadJdbc(MessageArchiveProperties props,
                                        DataSource dataSource,
                                        @Qualifier("archiveWriteJdbc") JdbcTemplate archiveWriteJdbc) {
        if (props.readDatasource().configured()) {
            return new JdbcTemplate(buildDedicated(props.readDatasource(), "archive-read"));
        }
        if (props.writeDatasource().configured()) {
            return archiveWriteJdbc;
        }
        return new JdbcTemplate(dataSource);
    }

    private static DataSource buildDedicated(MessageArchiveProperties.WriteDataSource cfg, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(cfg.url());
        if (cfg.username() != null && !cfg.username().isBlank()) {
            config.setUsername(cfg.username());
        }
        if (cfg.password() != null && !cfg.password().isBlank()) {
            config.setPassword(cfg.password());
        }
        config.setMaximumPoolSize(cfg.maximumPoolSize());
        config.setPoolName(poolName);
        return new HikariDataSource(config);
    }

    private static DataSource buildDedicated(MessageArchiveProperties.ReadDataSource cfg, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(cfg.url());
        if (cfg.username() != null && !cfg.username().isBlank()) {
            config.setUsername(cfg.username());
        }
        if (cfg.password() != null && !cfg.password().isBlank()) {
            config.setPassword(cfg.password());
        }
        config.setMaximumPoolSize(cfg.maximumPoolSize());
        config.setPoolName(poolName);
        return new HikariDataSource(config);
    }
}
