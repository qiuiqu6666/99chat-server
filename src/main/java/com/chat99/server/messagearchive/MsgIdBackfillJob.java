package com.chat99.server.messagearchive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
@ConditionalOnProperty(name = "chat99.message-archive.msg-id-backfill.enabled", havingValue = "true")
@ConditionalOnMessageArchiveWorker
public class MsgIdBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(MsgIdBackfillJob.class);

    private final MsgIdBackfillService backfillService;

    public MsgIdBackfillJob(MsgIdBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Scheduled(fixedDelayString = "${chat99.message-archive.msg-id-backfill.fixed-delay-ms:60000}")
    public void run() {
        try {
            int n = backfillService.backfillOnce();
            if (n > 0) {
                log.info("msgId backfill job updated={}", n);
            }
        } catch (Exception e) {
            log.warn("msgId backfill job failed: {}", e.toString());
        }
    }
}
