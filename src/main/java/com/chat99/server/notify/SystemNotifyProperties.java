package com.chat99.server.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.system-notify")
public record SystemNotifyProperties(
    String senderUserId,
    String senderDisplayName,
    String senderFaceUrl,
    boolean bootstrapOnStartup,
    boolean autoFriendOnRegister,
    String friendAddSource,
    boolean registerWelcomeEnabled,
    String registerWelcomeMessage) {

    public SystemNotifyProperties {
        if (senderUserId == null || senderUserId.isBlank()) {
            senderUserId = "99Messenger";
        }
        if (senderDisplayName == null || senderDisplayName.isBlank()) {
            senderDisplayName = "99Messenger";
        }
        if (friendAddSource == null || friendAddSource.isBlank()) {
            friendAddSource = "AddSource_Type_Server";
        }
        if (registerWelcomeMessage == null || registerWelcomeMessage.isBlank()) {
            registerWelcomeMessage = "欢迎使用99 Messenger。";
        }
    }

    public String friendAddWording() {
        return registerWelcomeMessage;
    }
}
