package com.chat99.server.integration;

import com.aliyun.oss.model.OSSObjectSummary;
import com.chat99.server.oss.OssClient;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每天清理 OSS 上超过保留期（默认 7 天）的三公报表图片。 */
@Component
public class SangongReportImageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SangongReportImageCleanupJob.class);

    private final OssClient ossClient;
    private final int retentionDays;

    public SangongReportImageCleanupJob(
            OssClient ossClient,
            @Value("${sangong.report-image-retention-days:7}") int retentionDays) {
        this.ossClient = ossClient;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${sangong.report-image-cleanup-cron:0 30 4 * * *}")
    public void cleanup() {
        if (!ossClient.isConfigured()) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = 0;
        int failed = 0;
        try {
            for (OSSObjectSummary obj : ossClient.listObjects(
                    IntegrationReportImageController.SangongReportImageStorage.PREFIX)) {
                if (obj.getLastModified() == null || !obj.getLastModified().toInstant().isBefore(cutoff)) {
                    continue;
                }
                try {
                    ossClient.deleteObject(obj.getKey());
                    deleted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("sangong report image delete failed key={} err={}", obj.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("sangong report image cleanup aborted: {}", e.getMessage());
            return;
        }
        if (deleted > 0 || failed > 0) {
            log.info("sangong report image cleanup done retentionDays={} deleted={} failed={}",
                retentionDays, deleted, failed);
        }
    }
}
