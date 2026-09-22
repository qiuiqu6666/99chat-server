package com.chat99.server.call;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CallAvCallBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CallAvCallBackfillRunner.class);

    private final CallCallbackLogRepository callbackLogRepository;
    private final CallWebhookService webhookService;

    public CallAvCallBackfillRunner(CallCallbackLogRepository callbackLogRepository,
                                    CallWebhookService webhookService) {
        this.callbackLogRepository = callbackLogRepository;
        this.webhookService = webhookService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<CallCallbackLog> logs = callbackLogRepository.findByPayloadJsonContaining("av_call");
        int merged = 0;
        for (CallCallbackLog entry : logs) {
            try {
                webhookService.reprocessStoredPayload(entry.getPayloadJson());
                merged++;
            } catch (Exception e) {
                log.warn("av_call backfill failed logId={}: {}", entry.getId(), e.getMessage());
            }
        }
        if (merged > 0) {
            log.info("av_call backfill scanned={} entries", merged);
        }
    }
}
