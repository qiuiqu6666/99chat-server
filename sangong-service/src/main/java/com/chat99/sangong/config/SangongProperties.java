package com.chat99.sangong.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sangong")
public class SangongProperties {
    /** 主服务 App JWT 密钥（共享验签），玩家鉴权唯一凭据 */
    private String chat99JwtSecret = "";
    private String settingsWriteKey = "";
    private String imCallbackKey = "";
    private String appUrl = "http://127.0.0.1:8088";
    private String storageDir = "data";
    private Im im = new Im();
    private Integration integration = new Integration();
    private Image image = new Image();
    private Realtime realtime = new Realtime();
    private Kafka kafka = new Kafka();

    public String getChat99JwtSecret() { return chat99JwtSecret; }
    public void setChat99JwtSecret(String chat99JwtSecret) { this.chat99JwtSecret = chat99JwtSecret; }
    public String getSettingsWriteKey() { return settingsWriteKey; }
    public void setSettingsWriteKey(String settingsWriteKey) { this.settingsWriteKey = settingsWriteKey; }
    public String getImCallbackKey() { return imCallbackKey; }
    public void setImCallbackKey(String imCallbackKey) { this.imCallbackKey = imCallbackKey; }
    public String getAppUrl() { return appUrl; }
    public void setAppUrl(String appUrl) { this.appUrl = appUrl; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public Im getIm() { return im; }
    public void setIm(Im im) { this.im = im; }
    public Integration getIntegration() { return integration; }
    public void setIntegration(Integration integration) { this.integration = integration; }
    public Image getImage() { return image; }
    public void setImage(Image image) { this.image = image; }
    public Realtime getRealtime() { return realtime; }
    public void setRealtime(Realtime realtime) { this.realtime = realtime; }
    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }

    public static class Im {
        private long sdkAppId; private String key = ""; private String restAdmin = "administrator";
        private String restBaseUrl = "https://adminapisgp.im.qcloud.com/v4";
        private String botUserId = "bot_sangong"; private String groupGameId = "";
        private String groupAdminStatsId = ""; private String groupLedgerId = "";
        public long getSdkAppId() { return sdkAppId; } public void setSdkAppId(long v) { sdkAppId = v; }
        public String getKey() { return key; } public void setKey(String v) { key = v; }
        public String getRestAdmin() { return restAdmin; } public void setRestAdmin(String v) { restAdmin = v; }
        public String getRestBaseUrl() { return restBaseUrl; } public void setRestBaseUrl(String v) { restBaseUrl = v; }
        public String getBotUserId() { return botUserId; } public void setBotUserId(String v) { botUserId = v; }
        public String getGroupGameId() { return groupGameId; } public void setGroupGameId(String v) { groupGameId = v; }
        public String getGroupAdminStatsId() { return groupAdminStatsId; } public void setGroupAdminStatsId(String v) { groupAdminStatsId = v; }
        public String getGroupLedgerId() { return groupLedgerId; } public void setGroupLedgerId(String v) { groupLedgerId = v; }
    }
    public static class Integration {
        private String baseUrl = ""; private String apiToken = "";
        public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
        public String getApiToken() { return apiToken; } public void setApiToken(String v) { apiToken = v; }
    }
    public static class Image {
        private int scale = 3; private int jpegQuality = 85; private String betReportTitle = "三公";
        public int getScale() { return scale; } public void setScale(int v) { scale = v; }
        public int getJpegQuality() { return jpegQuality; } public void setJpegQuality(int v) { jpegQuality = v; }
        public String getBetReportTitle() { return betReportTitle; } public void setBetReportTitle(String v) { betReportTitle = v; }
    }
    public static class Realtime {
        private int pollMs = 500; private int heartbeatSeconds = 15;
        public int getPollMs() { return pollMs; } public void setPollMs(int v) { pollMs = v; }
        public int getHeartbeatSeconds() { return heartbeatSeconds; } public void setHeartbeatSeconds(int v) { heartbeatSeconds = v; }
    }
    public static class Kafka {
        private boolean enabled = false;
        private String topicAfterSend = "chat99.im.after-send";
        private String topicGroupRecall = "chat99.im.group-recall";
        private String consumerGroup = "sangong-im-consumer";
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
        public String getTopicAfterSend() { return topicAfterSend; } public void setTopicAfterSend(String v) { topicAfterSend = v; }
        public String getTopicGroupRecall() { return topicGroupRecall; } public void setTopicGroupRecall(String v) { topicGroupRecall = v; }
        public String getConsumerGroup() { return consumerGroup; } public void setConsumerGroup(String v) { consumerGroup = v; }
    }
}
