package com.chat99.server.robot.agent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class AgentReportExportJob {

    private static final Logger log = LoggerFactory.getLogger(AgentReportExportJob.class);

    private final AgentReportExportService exportService;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public AgentReportExportJob(AgentReportExportService exportService) {
        this.exportService = exportService;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-report-export");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::processNext, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void processNext() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            exportService.processNextPending();
        } catch (Exception e) {
            log.error("agent report export job failed", e);
        } finally {
            running.set(false);
        }
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupExpired() {
        int deleted = exportService.cleanupExpired();
        if (deleted > 0) {
            log.info("cleaned {} expired agent report export tasks", deleted);
        }
    }
}
