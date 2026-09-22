package com.chat99.server.messagearchive;

import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
@ConditionalOnMessageArchiveWorker
public class ChatMessageTableProvisionerJob {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageTableProvisionerJob.class);

    private final ChatMessageWriteRepository writeRepository;
    private final ChatMessageTableRouter tableRouter;

    public ChatMessageTableProvisionerJob(ChatMessageWriteRepository writeRepository,
                                          ChatMessageTableRouter tableRouter) {
        this.writeRepository = writeRepository;
        this.tableRouter = tableRouter;
    }

    @Scheduled(cron = "0 0 3 25 * ?")
    public void provisionNextMonth() {
        Instant nextMonth = Instant.now().atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        String table = tableRouter.physicalTable(nextMonth);
        String suffix = tableRouter.tableSuffix(nextMonth);
        writeRepository.ensureTable(table, suffix);
        log.info("chat message table provisioned table={}", table);
    }
}
