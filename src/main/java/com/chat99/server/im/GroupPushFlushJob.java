package com.chat99.server.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GroupPushFlushJob {

    private static final Logger log = LoggerFactory.getLogger(GroupPushFlushJob.class);

    private final GroupPushAggregationService aggregationService;

    public GroupPushFlushJob(GroupPushAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @Scheduled(fixedDelayString = "${chat99.im.callback.group-push-flush-interval-ms:2000}")
    public void flushDueAggregations() {
        try {
            aggregationService.flushDue();
        } catch (Exception e) {
            log.warn("group push flush job failed err={}", e.getMessage());
        }
    }
}
