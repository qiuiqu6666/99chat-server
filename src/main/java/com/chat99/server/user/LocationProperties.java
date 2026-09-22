package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.location")
public record LocationProperties(
    /** 单用户最小写入间隔（秒），默认 3 小时 */
    int minIntervalSeconds,
    /** accuracy 合理上限（米） */
    double maxAccuracyMeters,
    /** collectedAt 允许相对服务器时钟的最大超前（秒） */
    int maxFutureSkewSeconds,
    /** 历史采样保留天数 */
    int historyRetentionDays,
    /** 是否启用逆地理（异步，不阻塞上报） */
    boolean geocodeEnabled,
    /** 高德 Web 服务 Key；空则回退 Nominatim */
    String amapKey,
    /** 逆地理 HTTP 超时（毫秒） */
    int geocodeTimeoutMs
) {
    public LocationProperties {
        if (minIntervalSeconds <= 0) {
            minIntervalSeconds = 3 * 60 * 60;
        }
        if (maxAccuracyMeters <= 0) {
            maxAccuracyMeters = 50_000;
        }
        if (maxFutureSkewSeconds <= 0) {
            maxFutureSkewSeconds = 300;
        }
        if (historyRetentionDays <= 0) {
            historyRetentionDays = 30;
        }
        if (amapKey == null) {
            amapKey = "";
        }
        if (geocodeTimeoutMs <= 0) {
            geocodeTimeoutMs = 2500;
        }
    }
}
