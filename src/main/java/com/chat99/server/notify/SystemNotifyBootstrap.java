package com.chat99.server.notify;

import com.chat99.server.im.ImAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时导入系统通知 IM 账号（假公众号发件人）。 */
@Component
@Order(50)
public class SystemNotifyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemNotifyBootstrap.class);

    private final SystemNotifyProperties props;
    private final ImAdminClient imAdmin;

    public SystemNotifyBootstrap(SystemNotifyProperties props, ImAdminClient imAdmin) {
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
            log.debug("system notify sender account check: userId={} exists={}", senderId, exists);
            
            if (!exists) {
                imAdmin.accountImport(senderId, props.senderDisplayName(), props.senderFaceUrl());
                log.info("system notify sender imported userId={} faceUrl={}", senderId, props.senderFaceUrl());
            } else {
                log.info("system notify sender exists userId={} skip faceUrl update", senderId);
            }
            imAdmin.setAllowTypeAllowAny(senderId);
            log.info("system notify sender bootstrap ok userId={}", senderId);
        } catch (Exception e) {
            log.warn("system notify sender bootstrap failed userId={} err={}", senderId, e.getMessage());
        }
    }
}
