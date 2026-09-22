package com.chat99.server.chatattachment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatAttachmentCleanupJob {

    private final ChatAttachmentCleanupService cleanupService;

    public ChatAttachmentCleanupJob(ChatAttachmentCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(cron = "${chat99.chat-attachment.jobs.cleanup-cron:0 20 4 * * ?}")
    public void run() {
        cleanupService.markUnreferenced();
        cleanupService.deleteDue();
    }
}
