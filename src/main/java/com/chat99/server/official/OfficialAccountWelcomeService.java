package com.chat99.server.official;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OfficialAccountWelcomeService {

    private static final Logger log = LoggerFactory.getLogger(OfficialAccountWelcomeService.class);

    private final OfficialAccountProperties props;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;

    public OfficialAccountWelcomeService(OfficialAccountProperties props, ImAdminClient imAdmin,
                                         ImUserIdService imUserIdService) {
        this.props = props;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
    }

    /**
     * 用户关注公众号：IM 订阅 + 可选欢迎单聊。失败仅记日志（由调用方决定是否抛错）。
     */
    public void onSubscribed(String officialAccountId, String userId, boolean throwOnSubscribeFailure) {
        try {
            imAdmin.addSubscriber(officialAccountId, imUserIdService.toIm(userId));
            log.info("official account subscribed userId={} officialAccountId={}", userId, officialAccountId);
        } catch (ImRestException e) {
            log.warn("official account subscribe im failed userId={} officialAccountId={} code={} msg={}",
                userId, officialAccountId, e.imErrorCode(), e.getMessage());
            if (throwOnSubscribeFailure) {
                throw e;
            }
        } catch (Exception e) {
            log.warn("official account subscribe failed userId={} officialAccountId={} err={}",
                userId, officialAccountId, e.getMessage());
            if (throwOnSubscribeFailure) {
                throw e;
            }
        }
        sendWelcomeMessage(officialAccountId, userId);
    }

    /** 注册成功：自动关注主公众号并发送欢迎语（可配置关闭）。 */
    public void onUserRegistered(String userId) {
        if (!props.registerWelcomeEnabled()) {
            return;
        }
        String officialAccountId = props.primaryOfficialAccountId();
        if (officialAccountId == null || officialAccountId.isBlank()) {
            log.warn("register auto-subscribe skipped: primary-official-account-id not configured");
            return;
        }
        int attempts = 3;
        for (int i = 1; i <= attempts; i++) {
            try {
                imAdmin.addSubscriber(officialAccountId, imUserIdService.toIm(userId));
                log.info("register auto-subscribe ok userId={} officialAccountId={}", userId, officialAccountId);
                break;
            } catch (Exception e) {
                log.warn("register auto-subscribe attempt {}/{} failed userId={} err={}",
                    i, attempts, userId, e.getMessage());
            }
            if (i < attempts) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        sendWelcomeMessage(officialAccountId, userId);
    }

    private void sendWelcomeMessage(String officialAccountId, String userId) {
        if (!props.subscribeWelcomeEnabled()) {
            return;
        }
        String message = props.welcomeMessage();
        try {
            imAdmin.sendC2cText(officialAccountId, imUserIdService.toIm(userId), message);
            log.info("subscribe welcome sent userId={} officialAccountId={}", userId, officialAccountId);
        } catch (ImRestException e) {
            log.warn("subscribe welcome im failed userId={} officialAccountId={} code={} msg={}",
                userId, officialAccountId, e.imErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("subscribe welcome failed userId={} officialAccountId={} err={}",
                userId, officialAccountId, e.getMessage());
        }
    }
}
