package com.chat99.server.sync;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class ContactPlatformMatchBackfillJob {

    private static final Logger log =
        LoggerFactory.getLogger(ContactPlatformMatchBackfillJob.class);
    private static final int BATCH_SIZE = 500;
    private static final int MAX_ROWS_PER_RUN = 10_000;

    private final UserContactItemRepository contactRepository;
    private final ContactPlatformMatchService matchService;
    private ScheduledExecutorService executor;

    public ContactPlatformMatchBackfillJob(UserContactItemRepository contactRepository,
                                           ContactPlatformMatchService matchService) {
        this.contactRepository = contactRepository;
        this.matchService = matchService;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "contact-platform-match");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, 15, 300, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void runSafely() {
        try {
            backfill();
        } catch (Exception e) {
            log.warn("contact platform match backfill failed: {}", e.getMessage());
        }
    }

    public void backfill() {
        long cursor = 0;
        int scanned = 0;
        int matched = 0;
        while (scanned < MAX_ROWS_PER_RUN) {
            List<UserContactItem> rows = contactRepository
                .findByStatusAndPlatformUserFalseAndIdGreaterThanOrderByIdAsc(
                    1, cursor, PageRequest.of(0, BATCH_SIZE))
                .getContent();
            if (rows.isEmpty()) {
                break;
            }
            Map<String, ContactPlatformMatchService.Match> matches = matchService.match(
                rows.stream().map(row -> new ContactPlatformMatchService.ContactCandidate(
                    String.valueOf(row.getId()), row.getUserId(),
                    matchService.parsePhones(row.getPhonesJson()))).toList());
            for (UserContactItem row : rows) {
                ContactPlatformMatchService.Match match =
                    matches.get(String.valueOf(row.getId()));
                if (match != null && match.platformUser()) {
                    row.setPlatformUser(true);
                    row.setMatchedUserId(match.matchedUserId());
                    matched++;
                }
                cursor = Math.max(cursor, row.getId());
            }
            contactRepository.saveAll(
                rows.stream().filter(UserContactItem::isPlatformUser).toList());
            scanned += rows.size();
            if (rows.size() < BATCH_SIZE) {
                break;
            }
        }
        if (matched > 0) {
            log.info("contact platform match backfill scanned={} matched={}", scanned, matched);
        }
    }
}
