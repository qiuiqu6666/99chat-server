package com.chat99.server.official;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OfficialAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OfficialAccountBootstrap.class);

    private final OfficialAccountProperties props;
    private final OfficialAccountService service;

    public OfficialAccountBootstrap(OfficialAccountProperties props, OfficialAccountService service) {
        this.props = props;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.bootstrapOnStartup()) {
            return;
        }
        try {
            service.ensurePrimaryLinked();
            log.info("official account bootstrap ok primaryId={}", props.primaryOfficialAccountId());
        } catch (Exception e) {
            log.warn("official account bootstrap skipped: {}", e.getMessage());
        }
    }
}
