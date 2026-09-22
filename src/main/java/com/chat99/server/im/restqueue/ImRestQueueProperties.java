package com.chat99.server.im.restqueue;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.im.rest-queue")
public record ImRestQueueProperties(
    Boolean enabled,
    String topic,
    String topicDlq,
    String consumerGroup,
    Integer concurrency,
    Integer dedupeTtlSeconds,
    Integer breakerCooldownSeconds,
    Integer maxAttempts,
    Boolean localAuthImFallback,
    Boolean enqueueRoleRefreshOnAuth,
    Map<String, Integer> rateLimitPerSecond
) {
    public ImRestQueueProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (topic == null || topic.isBlank()) {
            topic = "chat99.im.rest.jobs";
        }
        if (topicDlq == null || topicDlq.isBlank()) {
            topicDlq = "chat99.im.rest.jobs.dlq";
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            consumerGroup = "chat99-im-rest-worker";
        }
        if (concurrency == null || concurrency <= 0) {
            concurrency = 1;
        }
        if (dedupeTtlSeconds == null || dedupeTtlSeconds <= 0) {
            dedupeTtlSeconds = 10;
        }
        if (breakerCooldownSeconds == null || breakerCooldownSeconds <= 0) {
            breakerCooldownSeconds = 10;
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 8;
        }
        if (localAuthImFallback == null) {
            localAuthImFallback = Boolean.FALSE;
        }
        if (enqueueRoleRefreshOnAuth == null) {
            enqueueRoleRefreshOnAuth = Boolean.FALSE;
        }
        Map<String, Integer> rates = new HashMap<>();
        if (rateLimitPerSecond != null) {
            rates.putAll(rateLimitPerSecond);
        }
        rates.putIfAbsent("get-role-in-group", 80);
        rates.putIfAbsent("get-group-info", 80);
        rates.putIfAbsent("get-joined-group-list", 50);
        rates.putIfAbsent("get-group-member-info", 80);
        rateLimitPerSecond = Map.copyOf(rates);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public int rateLimit(String apiKey) {
        Integer v = rateLimitPerSecond.get(apiKey);
        return v == null || v <= 0 ? 80 : v;
    }
}
