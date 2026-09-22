package com.chat99.server.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.push")
public record PushProperties(
    boolean enabled,
    boolean skipWhenOnline,
    Apns apns,
    Jpush jpush) {

    public PushProperties {
        if (apns == null) {
            apns = new Apns(false, "p12", "", "", "", "", "", "", "", "", true, ".voip");
        }
        if (jpush == null) {
            jpush = new Jpush(false, "", "", "", "", "https://api.jpush.cn",
                true, "secondary_push", "secondary_push", "secondary_push", "secondary_push",
                "secondary_push", "");
        }
    }

    public record Apns(
        boolean enabled,
        String authType,
        String teamId,
        String keyId,
        String bundleId,
        String p8Key,
        String p12Path,
        String p12Password,
        String voipP12Path,
        String voipP12Password,
        boolean production,
        String voipTopicSuffix) {

        public Apns {
            if (authType == null || authType.isBlank()) {
                authType = "p12";
            }
            if (voipTopicSuffix == null || voipTopicSuffix.isBlank()) {
                voipTopicSuffix = ".voip";
            }
        }

        public boolean usesP12() {
            if ("p8".equalsIgnoreCase(authType)) {
                return false;
            }
            if ("p12".equalsIgnoreCase(authType)) {
                return true;
            }
            return p12Path != null && !p12Path.isBlank();
        }
    }

    public record Jpush(
        boolean enabled,
        String appKey,
        String masterSecret,
        String appKeyFile,
        String masterSecretFile,
        String baseUrl,
        boolean thirdPartyEnabled,
        String huaweiDistribution,
        String honorDistribution,
        String oppoDistribution,
        String vivoDistribution,
        String xiaomiDistribution,
        String xiaomiChannelId) {}
}
