package com.chat99.server.adminapi;

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

@Component
public class AdminUserGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(AdminUserGenerationJob.class);

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private final AdminUserGenerationService service;
    private final AdminUserGenerationProperties properties;
    private ScheduledExecutorService executor;

    public AdminUserGenerationJob(AdminUserGenerationService service,
                                  AdminUserGenerationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "admin-user-generation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
            this::processNext, properties.pollIntervalMs(), properties.pollIntervalMs(),
            TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public void processNext() {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            AdminUserGenerationTask task = service.claimNextTask();
            if (task != null) {
                service.processTask(task);
            }
        } catch (Exception e) {
            log.error("admin user generation job failed", e);
        } finally {
            RUNNING.set(false);
        }
    }

    @Scheduled(cron = "0 20 3 * * ?")
    public void cleanupExpired() {
        int deleted = service.cleanupExpired();
        if (deleted > 0) {
            log.info("cleaned {} expired admin user generation tasks", deleted);
        }
    }
}
