package com.chat99.server.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.integration")
public record IntegrationApiProperties(String apiToken) {

    public IntegrationApiProperties {
        if (apiToken == null) {
            apiToken = "";
        }
    }

    public boolean configured() {
        return apiToken != null && !apiToken.isBlank();
    }
}
