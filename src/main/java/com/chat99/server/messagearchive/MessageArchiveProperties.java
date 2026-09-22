package com.chat99.server.messagearchive;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.message-archive")
public record MessageArchiveProperties(
    boolean enabled,
    int webhookProducerTimeoutMs,
    int historyRateLimitPerSecond,
    Consumer consumer,
    WriteDataSource writeDatasource,
    ReadDataSource readDatasource,
    Kafka kafka,
    long lagMonitorIntervalMs,
    Snapshot snapshot,
    MsgIdBackfill msgIdBackfill) {

    public MessageArchiveProperties {
        if (webhookProducerTimeoutMs <= 0) {
            webhookProducerTimeoutMs = 500;
        }
        if (historyRateLimitPerSecond <= 0) {
            historyRateLimitPerSecond = 10;
        }
        if (lagMonitorIntervalMs <= 0) {
            lagMonitorIntervalMs = 60_000L;
        }
        if (consumer == null) {
            consumer = new Consumer(true, true, 500, 3, 4, 4);
        }
        if (writeDatasource == null) {
            writeDatasource = new WriteDataSource("", "", "", 10);
        }
        if (readDatasource == null) {
            readDatasource = new ReadDataSource("", "", "", 15);
        }
        if (kafka == null) {
            kafka = new Kafka(
                "chat99.im.after-send",
                "chat99.im.archive.dlq",
                "chat99.im.push.dlq",
                "chat99.im.group-recall",
                "chat99-im-archive-writer",
                "chat99-im-push-dispatcher");
        }
        if (snapshot == null) {
            snapshot = Snapshot.defaults();
        } else {
            snapshot = snapshot.normalized();
        }
        if (msgIdBackfill == null) {
            msgIdBackfill = MsgIdBackfill.defaults();
        } else {
            msgIdBackfill = msgIdBackfill.normalized();
        }
    }

    public record Consumer(
        boolean archiveEnabled,
        boolean pushEnabled,
        int batchSize,
        int maxRetries,
        int archiveConcurrency,
        int pushConcurrency) {

        public Consumer {
            if (batchSize <= 0) {
                batchSize = 500;
            }
            if (maxRetries <= 0) {
                maxRetries = 3;
            }
            if (archiveConcurrency <= 0) {
                archiveConcurrency = 4;
            }
            if (pushConcurrency <= 0) {
                pushConcurrency = 4;
            }
        }
    }

    public record WriteDataSource(String url, String username, String password, int maximumPoolSize) {
        public WriteDataSource {
            if (maximumPoolSize <= 0) {
                maximumPoolSize = 10;
            }
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }

    public record ReadDataSource(String url, String username, String password, int maximumPoolSize) {
        public ReadDataSource {
            if (maximumPoolSize <= 0) {
                maximumPoolSize = 15;
            }
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }

    public record Kafka(
        String topicAfterSend,
        String topicDlq,
        String topicPushDlq,
        String topicGroupRecall,
        String archiveConsumerGroup,
        String pushConsumerGroup) {

        public Kafka {
            if (topicPushDlq == null || topicPushDlq.isBlank()) {
                topicPushDlq = "chat99.im.push.dlq";
            }
            if (topicGroupRecall == null || topicGroupRecall.isBlank()) {
                topicGroupRecall = "chat99.im.group-recall";
            }
        }
    }

    public record Snapshot(
        int recentDays,
        int defaultLimitConv,
        int maxLimitConv,
        int defaultLimitC2c,
        int defaultLimitGroup,
        int maxLimitC2c,
        int maxLimitGroup,
        int defaultLimitMsg,
        int minLimitMsg,
        int maxLimitMsg,
        long timeoutMs,
        int rateLimitPerSecond,
        int candidateOversample,
        String projectionMode) {

        static Snapshot defaults() {
            return new Snapshot(7, 20, 50, 40, 40, 50, 50, 40, 30, 50, 3000L, 2, 3, "hybrid").normalized();
        }

        Snapshot normalized() {
            int days = recentDays <= 0 ? 7 : recentDays;
            int defConv = defaultLimitConv <= 0 ? 20 : defaultLimitConv;
            int maxConv = maxLimitConv <= 0 ? 50 : maxLimitConv;
            if (defConv > maxConv) {
                defConv = maxConv;
            }
            int maxC2c = maxLimitC2c <= 0 ? 50 : maxLimitC2c;
            int maxGroup = maxLimitGroup <= 0 ? 50 : maxLimitGroup;
            int defC2c = defaultLimitC2c <= 0 ? 40 : defaultLimitC2c;
            int defGroup = defaultLimitGroup <= 0 ? 40 : defaultLimitGroup;
            if (defC2c > maxC2c) {
                defC2c = maxC2c;
            }
            if (defGroup > maxGroup) {
                defGroup = maxGroup;
            }
            int minMsg = minLimitMsg <= 0 ? 30 : minLimitMsg;
            int maxMsg = maxLimitMsg <= 0 ? 50 : maxLimitMsg;
            if (minMsg > maxMsg) {
                minMsg = maxMsg;
            }
            int defMsg = defaultLimitMsg <= 0 ? 40 : defaultLimitMsg;
            if (defMsg < minMsg) {
                defMsg = minMsg;
            }
            if (defMsg > maxMsg) {
                defMsg = maxMsg;
            }
            long timeout = timeoutMs <= 0 ? 3000L : timeoutMs;
            int rate = rateLimitPerSecond <= 0 ? 2 : rateLimitPerSecond;
            int oversample = candidateOversample <= 0 ? 3 : candidateOversample;
            String mode = projectionMode == null || projectionMode.isBlank()
                ? "hybrid"
                : projectionMode.trim().toLowerCase();
            if (!mode.equals("recent") && !mode.equals("hybrid") && !mode.equals("legacy")) {
                mode = "hybrid";
            }
            return new Snapshot(
                days, defConv, maxConv, defC2c, defGroup, maxC2c, maxGroup,
                defMsg, minMsg, maxMsg, timeout, rate, oversample, mode);
        }
    }

    public record MsgIdBackfill(
        boolean enabled,
        int batchConversations,
        int batchGroups,
        long sleepMs,
        int maxTablesPerRun,
        int roamMaxCnt,
        int maxPagesPerEntity,
        boolean skipEnabled,
        int skipTtlDays) {

        static MsgIdBackfill defaults() {
            return new MsgIdBackfill(false, 20, 10, 200L, 3, 100, 20, true, 30).normalized();
        }

        MsgIdBackfill normalized() {
            int batch = batchConversations <= 0 ? 20 : Math.min(batchConversations, 200);
            int groups = batchGroups <= 0 ? 10 : Math.min(batchGroups, 100);
            long sleep = sleepMs < 0 ? 200L : sleepMs;
            int tables = maxTablesPerRun <= 0 ? 3 : Math.min(maxTablesPerRun, 24);
            int roam = roamMaxCnt <= 0 ? 100 : Math.min(roamMaxCnt, 100);
            int pages = maxPagesPerEntity <= 0 ? 20 : Math.min(maxPagesPerEntity, 200);
            int ttl = skipTtlDays <= 0 ? 30 : Math.min(skipTtlDays, 365);
            return new MsgIdBackfill(
                enabled, batch, groups, sleep, tables, roam, pages, skipEnabled, ttl);
        }
    }
}
