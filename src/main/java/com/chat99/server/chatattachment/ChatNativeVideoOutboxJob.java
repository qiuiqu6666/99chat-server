package com.chat99.server.chatattachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChatNativeVideoOutboxJob {

    private static final Logger log = LoggerFactory.getLogger(ChatNativeVideoOutboxJob.class);

    private final ChatNativeVideoService nativeVideoService;

    public ChatNativeVideoOutboxJob(ChatNativeVideoService nativeVideoService) {
        this.nativeVideoService = nativeVideoService;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    public void run() {
        try {
            int n = nativeVideoService.resumeDue(8);
            if (n > 0) {
                log.info("chat native-video outbox resumed={}", n);
            }
        } catch (Exception e) {
            log.warn("chat native-video outbox failed err={}", e.getMessage());
        }
    }
}
