package com.chat99.server.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从已归档的 chat_message 表回补 av_call 通话记录（按时间升序，保证邀请→接听→挂断顺序）。
 */
@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class CallAvCallArchiveBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CallAvCallArchiveBackfillRunner.class);

    private final JdbcTemplate jdbc;
    private final CallWebhookService callWebhookService;
    private final ObjectMapper json;

    public CallAvCallArchiveBackfillRunner(@Qualifier("archiveReadJdbc") JdbcTemplate jdbc,
                                           CallWebhookService callWebhookService,
                                           ObjectMapper json) {
        this.jdbc = jdbc;
        this.callWebhookService = callWebhookService;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> tables = jdbc.queryForList(
            "SELECT physical_table FROM chat_message_table_registry ORDER BY table_suffix",
            String.class);
        if (tables.isEmpty()) {
            tables = List.of("chat_message_202606");
        }
        int merged = 0;
        for (String table : tables) {
            merged += backfillTable(table);
        }
        if (merged > 0) {
            log.info("av_call archive backfill processed {} messages", merged);
        }
    }

    private int backfillTable(String table) {
        if (!table.matches("chat_message_\\d{6}")) {
            return 0;
        }
        String sql = "SELECT from_account, peer_account, msg_time_ms, msg_body_json FROM "
            + table + " WHERE msg_body_json LIKE '%av_call%' ORDER BY msg_time_ms ASC";
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (Exception e) {
            log.debug("av_call archive backfill skip table {}: {}", table, e.getMessage());
            return 0;
        }
        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> imBody = CallAvCallImBodyBuilder.fromArchiveRow(json, row);
                if (imBody != null) {
                    callWebhookService.tryMergeFromImBody(imBody);
                    count++;
                }
            } catch (Exception e) {
                log.debug("av_call archive backfill row skip: {}", e.getMessage());
            }
        }
        return count;
    }
}
