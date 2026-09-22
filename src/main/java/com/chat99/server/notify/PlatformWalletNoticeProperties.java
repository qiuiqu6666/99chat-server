package com.chat99.server.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.platform-wallet-notice")
public record PlatformWalletNoticeProperties(
    String senderUserId,
    String senderDisplayName,
    String senderFaceUrl,
    String serviceName,
    boolean bootstrapOnStartup,
    boolean autoFriendOnRegister,
    String friendAddSource,
    boolean notifyEnabled) {

    public PlatformWalletNoticeProperties {
        if (senderUserId == null || senderUserId.isBlank()) {
            senderUserId = "99Chat";
        }
        if (senderDisplayName == null || senderDisplayName.isBlank()) {
            senderDisplayName = "99Chat支付助手";
        }
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = "支付助手";
        }
        if (friendAddSource == null || friendAddSource.isBlank()) {
            friendAddSource = "AddSource_Type_Server";
        }
    }
}
