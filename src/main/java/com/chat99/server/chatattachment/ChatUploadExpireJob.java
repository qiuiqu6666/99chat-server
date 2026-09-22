package com.chat99.server.chatattachment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatUploadExpireJob {

    private final ChatAttachmentCleanupService cleanupService;

    public ChatUploadExpireJob(ChatAttachmentCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${chat99.chat-attachment.jobs.expire-fixed-delay-ms:300000}")
    public void run() {
        cleanupService.expireUploads();
    }
}
