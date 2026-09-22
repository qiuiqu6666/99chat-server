package com.chat99.server.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.telegram-ops")
public record TelegramOpsProperties(
    boolean enabled,
    String botToken,
    String chatId,
    boolean notifyDeposit,
    boolean notifyWithdraw,
    boolean notifySweep,
    boolean queryEnabled,
    long pollTimeoutSeconds,
    String gameControlChatId,
    boolean gameControlEnabled) {

    public TelegramOpsProperties {
        if (botToken == null) {
            botToken = "";
        }
        if (chatId == null) {
            chatId = "";
        }
        if (gameControlChatId == null) {
            gameControlChatId = "";
        }
        if (pollTimeoutSeconds <= 0) {
            pollTimeoutSeconds = 25;
        }
    }

    public boolean isReady() {
        return enabled
            && botToken != null && !botToken.isBlank()
            && chatId != null && !chatId.isBlank();
    }

    public boolean isQueryReady() {
        return isReady() && queryEnabled;
    }

    /** 通知可只依赖 ops chat；游戏控制另有独立群。机器人 token 共用。 */
    public boolean isBotTokenReady() {
        return enabled && botToken != null && !botToken.isBlank();
    }

    public boolean isGameControlReady() {
        return isBotTokenReady()
            && gameControlEnabled
            && gameControlChatId != null && !gameControlChatId.isBlank();
    }

    public boolean isPollerReady() {
        return isQueryReady() || isGameControlReady();
    }
}
