package com.chat99.server.official;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.official-account")
public record OfficialAccountProperties(
    String idPrefix,
    String defaultOwnerUserId,
    int defaultMaxSubscribers,
    String primaryOfficialAccountId,
    String primarySlug,
    boolean bootstrapOnStartup,
    boolean registerWelcomeEnabled,
    boolean subscribeWelcomeEnabled,
    String welcomeMessage,
    String registerWelcomeMessage) {

    public OfficialAccountProperties {
        if (idPrefix == null || idPrefix.isBlank()) {
            idPrefix = "@TOA#_99chat_";
        }
        if (!idPrefix.startsWith("@TOA#_")) {
            throw new IllegalArgumentException("chat99.official-account.id-prefix must start with @TOA#_");
        }
        if (idPrefix.contains("@TOA#_@TOA#")) {
            throw new IllegalArgumentException("chat99.official-account.id-prefix cannot contain @TOA#_@TOA#");
        }
        if (defaultMaxSubscribers <= 0) {
            defaultMaxSubscribers = 100_000;
        }
        if (primarySlug == null || primarySlug.isBlank()) {
            primarySlug = "official";
        }
        if (welcomeMessage == null || welcomeMessage.isBlank()) {
            welcomeMessage = registerWelcomeMessage;
        }
        if (welcomeMessage == null || welcomeMessage.isBlank()) {
            welcomeMessage = "欢迎使用99 Messenger。";
        }
        registerWelcomeMessage = welcomeMessage;
    }
}
