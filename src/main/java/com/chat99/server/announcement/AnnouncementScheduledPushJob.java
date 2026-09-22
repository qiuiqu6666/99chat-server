package com.chat99.server.announcement;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AnnouncementScheduledPushJob {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementScheduledPushJob.class);

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementService announcementService;

    public AnnouncementScheduledPushJob(AnnouncementRepository announcementRepository,
                                          AnnouncementService announcementService) {
        this.announcementRepository = announcementRepository;
        this.announcementService = announcementService;
    }

    @Scheduled(fixedDelayString = "${chat99.announcement.scheduled-push-interval-ms:30000}")
    public void publishDueAnnouncements() {
        Instant now = Instant.now();
        List<Announcement> due = announcementRepository
            .findByStatusAndPublishAtLessThanEqualOrderByPublishAtAsc(AnnouncementStatus.SCHEDULED, now);
        if (due.isEmpty()) {
            return;
        }
        for (Announcement announcement : due) {
            try {
                if (announcementService.publishDueScheduled(announcement.getId())) {
                    log.info("announcement scheduled push fired id={} publishAt={}",
                        announcement.getId(), announcement.getPublishAt());
                }
            } catch (Exception e) {
                log.error("announcement scheduled push failed id={} err={}",
                    announcement.getId(), e.getMessage(), e);
            }
        }
    }
}
