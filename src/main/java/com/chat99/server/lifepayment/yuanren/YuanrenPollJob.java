package com.chat99.server.lifepayment.yuanren;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class YuanrenPollJob {

    private static final Logger log = LoggerFactory.getLogger(YuanrenPollJob.class);

    private final YuanrenProperties props;
    private final YuanrenFulfillmentService fulfillmentService;

    public YuanrenPollJob(YuanrenProperties props, YuanrenFulfillmentService fulfillmentService) {
        this.props = props;
        this.fulfillmentService = fulfillmentService;
    }

    @Scheduled(fixedDelayString = "${chat99.life-payment.yuanren.poll-interval-ms:60000}")
    public void poll() {
        if (!props.isConfigured() || !props.isPollEnabled()) {
            return;
        }
        try {
            fulfillmentService.pollPendingOrders();
        } catch (Exception e) {
            log.warn("yuanren poll job error: {}", e.toString());
        }
    }
}
