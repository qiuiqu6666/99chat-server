package com.chat99.server.wallet;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.im.wallet-card-guard")
public record WalletOrderCardGuardProperties(
    boolean enabled,
    boolean enforce,
    boolean logOnly,
    long dedupeTtlDays,
    List<String> allowSenderIds) {

    public WalletOrderCardGuardProperties {
        if (dedupeTtlDays <= 0) {
            dedupeTtlDays = 90;
        }
        if (allowSenderIds == null || allowSenderIds.isEmpty()) {
            allowSenderIds = List.of("administrator");
        }
    }
}
