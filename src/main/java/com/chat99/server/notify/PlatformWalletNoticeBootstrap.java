package com.chat99.server.notify;

import com.chat99.server.im.ImAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(51)
public class PlatformWalletNoticeBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformWalletNoticeBootstrap.class);

    private final PlatformWalletNoticeProperties props;
    private final ImAdminClient imAdmin;

    public PlatformWalletNoticeBootstrap(PlatformWalletNoticeProperties props, ImAdminClient imAdmin) {
        this.props = props;
        this.imAdmin = imAdmin;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.bootstrapOnStartup()) {
            return;
        }
        String senderId = props.senderUserId();
        try {
            boolean exists = imAdmin.accountExists(senderId);
            log.debug("platform wallet notice sender account check: userId={} exists={}", senderId, exists);
            
            if (!exists) {
                imAdmin.accountImport(senderId, props.senderDisplayName(), props.senderFaceUrl());
                log.info("platform wallet notice sender imported userId={} faceUrl={}", senderId, props.senderFaceUrl());
            } else {
                log.info("platform wallet notice sender exists userId={} skip faceUrl update", senderId);
            }
            imAdmin.setAllowTypeAllowAny(senderId);
            log.info("platform wallet notice sender bootstrap ok userId={}", senderId);
        } catch (Exception e) {
            log.warn("platform wallet notice sender bootstrap failed userId={} err={}", senderId, e.getMessage());
        }
    }
}
