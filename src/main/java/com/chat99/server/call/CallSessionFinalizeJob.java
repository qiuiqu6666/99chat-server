package com.chat99.server.call;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CallSessionFinalizeJob {

    private final CallWebhookService callWebhookService;

    public CallSessionFinalizeJob(CallWebhookService callWebhookService) {
        this.callWebhookService = callWebhookService;
    }

    @Scheduled(fixedDelayString = "${chat99.trtc.callback.session-finalize-interval-ms:30000}")
    public void finalizeStaleSessions() {
        // 先窄表修膨胀时长，再扫未收尾会话（不再 findAll）
        callWebhookService.repairInflatedFinalizeDurations();
        callWebhookService.finalizeStaleSessions();
    }
}
