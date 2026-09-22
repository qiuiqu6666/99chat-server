package com.chat99.server.user;

import com.chat99.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginLogService {

    private final LoginLogRepository repo;
    private final ClientContext clientContext;

    public LoginLogService(LoginLogRepository repo, ClientContext clientContext) {
        this.repo = repo;
        this.clientContext = clientContext;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest http, String userId, String accountInput,
                       String loginType, String deviceId, boolean success, String failReason) {
        LoginLog log = new LoginLog();
        log.setUserId(userId);
        log.setAccountInput(accountInput);
        log.setLoginType(loginType);
        log.setClientVersion(clientContext.version(http));
        log.setClientPlatform(clientContext.platform(http));
        log.setDeviceId(deviceId);
        log.setIp(clientContext.ip(http));
        log.setUserAgent(clientContext.userAgent(http));
        log.setSuccess(success);
        log.setFailReason(failReason);
        repo.save(log);
    }

    /**
     * 网页扫码登录：确认请求来自手机，设备元数据须用 Web 创会话时快照，不能用手机请求头。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWebQrLogin(String userId, String webDeviceId, String webPlatform,
                                 String webIp, String webUserAgent, String webClientVersion,
                                 boolean success, String failReason) {
        LoginLog log = new LoginLog();
        log.setUserId(userId);
        log.setAccountInput(userId);
        log.setLoginType("QR_WEB");
        log.setClientVersion(blankToNull(webClientVersion));
        log.setClientPlatform(blankToNull(webPlatform));
        log.setDeviceId(webDeviceId);
        log.setIp(blankToNull(webIp));
        log.setUserAgent(blankToNull(webUserAgent));
        log.setSuccess(success);
        log.setFailReason(failReason);
        repo.save(log);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
