package com.chat99.server.chatattachment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatAttachmentBindReconcileJob {

    private final ChatAttachmentCleanupService cleanupService;

    public ChatAttachmentBindReconcileJob(ChatAttachmentCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${chat99.chat-attachment.jobs.reconcile-fixed-delay-ms:600000}")
    public void run() {
        cleanupService.reconcileReserved();
    }
}
