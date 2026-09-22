package com.chat99.server.lifepayment.yuanren;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.life-payment.yuanren")
public class YuanrenProperties {

    /** 总开关：开启后 mobile / electric 缴费走大猿人，不再派 Worker 充值/缴费任务。 */
    private boolean enabled = false;
    private String baseUrl = "";
    private String userid = "";
    private String apikey = "";
    /** 公网可达的异步回调地址，对应大猿人 notify_url。 */
    private String notifyUrl = "";
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 30_000;
    /**
     * 话费面额(元整数) → product_id，例：50=1001,100=1002。
     * 也可用 Map 绑定：mobile-product-ids.50=1001
     */
    private Map<String, String> mobileProductIds = new LinkedHashMap<>();
    /** 电费默认产品 ID；若配置了 electric-product-ids 则按面额优先。 */
    private String electricProductId = "";
    private Map<String, String> electricProductIds = new LinkedHashMap<>();
    private boolean pollEnabled = true;
    private long pollIntervalMs = 60_000L;
    private int pollBatchSize = 30;

    public boolean isConfigured() {
        return enabled
            && baseUrl != null && !baseUrl.isBlank()
            && userid != null && !userid.isBlank()
            && apikey != null && !apikey.isBlank()
            && notifyUrl != null && !notifyUrl.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getUserid() {
        return userid;
    }

    public void setUserid(String userid) {
        this.userid = userid == null ? "" : userid.trim();
    }

    public String getApikey() {
        return apikey;
    }

    public void setApikey(String apikey) {
        this.apikey = apikey == null ? "" : apikey.trim();
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public void setNotifyUrl(String notifyUrl) {
        this.notifyUrl = notifyUrl == null ? "" : notifyUrl.trim();
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = Math.max(connectTimeoutMs, 1000);
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = Math.max(readTimeoutMs, 1000);
    }

    public Map<String, String> getMobileProductIds() {
        return mobileProductIds == null ? Collections.emptyMap() : mobileProductIds;
    }

    public void setMobileProductIds(Map<String, String> mobileProductIds) {
        this.mobileProductIds = mobileProductIds == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mobileProductIds);
    }

    public String getElectricProductId() {
        return electricProductId;
    }

    public void setElectricProductId(String electricProductId) {
        this.electricProductId = electricProductId == null ? "" : electricProductId.trim();
    }

    public Map<String, String> getElectricProductIds() {
        return electricProductIds == null ? Collections.emptyMap() : electricProductIds;
    }

    public void setElectricProductIds(Map<String, String> electricProductIds) {
        this.electricProductIds = electricProductIds == null ? new LinkedHashMap<>() : new LinkedHashMap<>(electricProductIds);
    }

    public boolean isPollEnabled() {
        return pollEnabled;
    }

    public void setPollEnabled(boolean pollEnabled) {
        this.pollEnabled = pollEnabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = Math.max(pollIntervalMs, 5_000L);
    }

    public int getPollBatchSize() {
        return pollBatchSize;
    }

    public void setPollBatchSize(int pollBatchSize) {
        this.pollBatchSize = Math.min(Math.max(pollBatchSize, 1), 100);
    }
}
