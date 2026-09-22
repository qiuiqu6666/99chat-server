package com.chat99.server.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.im")
public record ImProperties(
    int userSigExpireSeconds,
    String restAdminAccount,
    String restBaseUrl,
    Boolean roleCacheEnabled,
    Integer roleCacheTtlSeconds
) {
    public ImProperties {
        if (userSigExpireSeconds <= 0) {
            userSigExpireSeconds = 90 * 24 * 3600;
        }
        if (restAdminAccount == null || restAdminAccount.isBlank()) {
            restAdminAccount = "administrator";
        }
        if (restBaseUrl == null || restBaseUrl.isBlank()) {
            restBaseUrl = "https://adminapisgp.im.qcloud.com/v4/";
        } else if (!restBaseUrl.endsWith("/")) {
            restBaseUrl = restBaseUrl + "/";
        }
        if (roleCacheEnabled == null) {
            roleCacheEnabled = Boolean.TRUE;
        }
        if (roleCacheTtlSeconds == null || roleCacheTtlSeconds <= 0) {
            roleCacheTtlSeconds = 60;
        }
    }
}
