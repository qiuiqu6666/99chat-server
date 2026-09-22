package com.chat99.server.robot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "robot.sync")
public record RobotSyncProperties(String secret, DatasourceConfig datasource) {

    public RobotSyncProperties {
        if (datasource == null) {
            datasource = new DatasourceConfig("", "", "", 10);
        }
    }

    public boolean secretConfigured() {
        return secret != null && !secret.isBlank();
    }

    public record DatasourceConfig(String url, String username, String password, int maximumPoolSize) {
        public DatasourceConfig {
            if (maximumPoolSize <= 0) {
                maximumPoolSize = 10;
            }
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }
}
