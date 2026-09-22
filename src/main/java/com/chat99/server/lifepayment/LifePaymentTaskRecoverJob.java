package com.chat99.server.lifepayment;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LifePaymentTaskRecoverJob {

    private final LifePaymentTaskService taskService;
    private final LifePaymentCatalogService catalogService;

    public LifePaymentTaskRecoverJob(LifePaymentTaskService taskService,
                                     LifePaymentCatalogService catalogService) {
        this.taskService = taskService;
        this.catalogService = catalogService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        catalogService.ensureSeedAmounts();
        taskService.recoverTimedOut();
    }

    @Scheduled(fixedDelayString = "${chat99.life-payment.recover-scan-interval-ms:15000}")
    public void recover() {
        taskService.recoverTimedOut();
    }
}
