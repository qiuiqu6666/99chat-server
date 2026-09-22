package com.chat99.server.chatattachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatAttachmentThumbnailBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentThumbnailBackfillJob.class);

    private final ChatAttachmentThumbnailExtractService extractService;

    public ChatAttachmentThumbnailBackfillJob(ChatAttachmentThumbnailExtractService extractService) {
        this.extractService = extractService;
    }

    @Scheduled(initialDelay = 8_000, fixedDelay = 120_000)
    public void run() {
        try {
            int bound = extractService.backfillReadyVideos(8);
            if (bound > 0) {
                log.info("chat-att thumb backfill bound={}", bound);
            }
        } catch (Exception e) {
            log.warn("chat-att thumb backfill failed err={}", e.getMessage());
        }
    }
}
