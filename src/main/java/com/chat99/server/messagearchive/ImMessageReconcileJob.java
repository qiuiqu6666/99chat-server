package com.chat99.server.messagearchive;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
@ConditionalOnMessageArchiveWorker
public class ImMessageReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ImMessageReconcileJob.class);

    private final ChatMessageTableRouter tableRouter;
    private final JdbcTemplate jdbc;

    public ImMessageReconcileJob(ChatMessageTableRouter tableRouter,
                                 @Qualifier("archiveWriteJdbc") JdbcTemplate jdbc) {
        this.tableRouter = tableRouter;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void reconcileYesterday() {
        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        String table = tableRouter.physicalTable(day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        long localCount = 0;
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            localCount = count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("im archive reconcile skip missing table={} err={}", table, e.getMessage());
            return;
        }
        jdbc.update(
            """
            INSERT INTO chat_message_reconcile_log (reconcile_day, im_count, local_count, gap_count, note)
            VALUES (?, NULL, ?, NULL, 'local_count_only; im_count pending IM stats API')
            ON DUPLICATE KEY UPDATE local_count=VALUES(local_count), note=VALUES(note)
            """,
            day, localCount);
        log.info("im archive reconcile day={} localCount={}", day, localCount);
    }
}
