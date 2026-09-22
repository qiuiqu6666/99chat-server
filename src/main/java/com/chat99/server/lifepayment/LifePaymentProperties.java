package com.chat99.server.lifepayment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.life-payment")
public class LifePaymentProperties {

    private boolean enabled = true;
    private int queryExpireMinutes = 10;
    private int taskHeartbeatTimeoutSeconds = 60;
    private int maxAttempts = 3;
    private long recoverScanIntervalMs = 15000;
    /** Bearer token for plugin/worker APIs; falls back to INTEGRATION_API_TOKEN when blank. */
    private String workerToken = "";
    /** 订单状态变更时向用户推送 IM 自定义消息 life_payment_order_update */
    private boolean orderUpdateNotifyEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getQueryExpireMinutes() {
        return queryExpireMinutes;
    }

    public void setQueryExpireMinutes(int queryExpireMinutes) {
        this.queryExpireMinutes = queryExpireMinutes;
    }

    public int getTaskHeartbeatTimeoutSeconds() {
        return taskHeartbeatTimeoutSeconds;
    }

    public void setTaskHeartbeatTimeoutSeconds(int taskHeartbeatTimeoutSeconds) {
        this.taskHeartbeatTimeoutSeconds = taskHeartbeatTimeoutSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRecoverScanIntervalMs() {
        return recoverScanIntervalMs;
    }

    public void setRecoverScanIntervalMs(long recoverScanIntervalMs) {
        this.recoverScanIntervalMs = recoverScanIntervalMs;
    }

    public String getWorkerToken() {
        return workerToken;
    }

    public void setWorkerToken(String workerToken) {
        this.workerToken = workerToken;
    }

    public boolean isOrderUpdateNotifyEnabled() {
        return orderUpdateNotifyEnabled;
    }

    public void setOrderUpdateNotifyEnabled(boolean orderUpdateNotifyEnabled) {
        this.orderUpdateNotifyEnabled = orderUpdateNotifyEnabled;
    }
}
