package com.chat99.server.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class CallAvCallIncrementalMergeJob {

    private static final Logger log = LoggerFactory.getLogger(CallAvCallIncrementalMergeJob.class);
    private static final long OVERLAP_MS = 120_000L;

    private final JdbcTemplate jdbc;
    private final CallWebhookService callWebhookService;
    private final CallRecordUserRepository recordRepository;
    private final ObjectMapper json;

    public CallAvCallIncrementalMergeJob(@Qualifier("archiveReadJdbc") JdbcTemplate jdbc,
                                         CallWebhookService callWebhookService,
                                         CallRecordUserRepository recordRepository,
                                         ObjectMapper json) {
        this.jdbc = jdbc;
        this.callWebhookService = callWebhookService;
        this.recordRepository = recordRepository;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${chat99.trtc.callback.incremental-merge-interval-ms:30000}")
    public void mergeIncremental() {
        long watermark = recordRepository.findMaxOccurredAt()
            .map(t -> Math.max(0L, t.toEpochMilli() - OVERLAP_MS))
            .orElse(0L);
        int merged = mergeSince(watermark);
        if (merged > 0) {
            log.info("av_call incremental merge processed {} messages sinceMs={}", merged, watermark);
        }
    }

    private int mergeSince(long sinceMs) {
        String table = resolveActiveTable();
        String sql = "SELECT from_account, peer_account, msg_time_ms, msg_body_json FROM "
            + table + " WHERE msg_body_json LIKE '%av_call%' AND msg_time_ms >= ? ORDER BY msg_time_ms ASC";
        var rows = jdbc.queryForList(sql, sinceMs);
        int count = 0;
        for (var row : rows) {
            try {
                var imBody = CallAvCallImBodyBuilder.fromArchiveRow(json, row);
                if (imBody != null) {
                    callWebhookService.tryMergeFromImBody(imBody);
                    count++;
                }
            } catch (Exception e) {
                log.debug("av_call incremental merge skip: {}", e.getMessage());
            }
        }
        return count;
    }

    private String resolveActiveTable() {
        var tables = jdbc.queryForList(
            "SELECT physical_table FROM chat_message_table_registry ORDER BY table_suffix DESC LIMIT 1",
            String.class);
        if (!tables.isEmpty()) {
            return tables.get(0);
        }
        return "chat_message_202606";
    }
}
