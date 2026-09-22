package com.chat99.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chat99.server.adminapi.AdminDevicesService;
import com.chat99.server.common.ClientContext;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.LoginLogService;
import com.chat99.server.user.PresenceService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDeviceService;
import com.chat99.server.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QrLoginService {

    private static final String DEFAULT_WEB_PLATFORM = "web";

    private final QrLoginSessionStore store;
    private final QrLoginProperties props;
    private final UserRepository userRepository;
    private final UserDeviceService deviceService;
    private final UserSessionService sessionService;
    private final PresenceService presenceService;
    private final AdminDevicesService adminDevicesService;
    private final ClientContext clientContext;
    private final LoginLogService loginLogService;
    private final ObjectMapper jsonMapper;

    public QrLoginService(QrLoginSessionStore store,
                          QrLoginProperties props,
                          UserRepository userRepository,
                          UserDeviceService deviceService,
                          UserSessionService sessionService,
                          PresenceService presenceService,
                          AdminDevicesService adminDevicesService,
                          ClientContext clientContext,
                          LoginLogService loginLogService,
                          ObjectMapper jsonMapper) {
        this.store = store;
        this.props = props;
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.sessionService = sessionService;
        this.presenceService = presenceService;
        this.adminDevicesService = adminDevicesService;
        this.clientContext = clientContext;
        this.loginLogService = loginLogService;
        this.jsonMapper = jsonMapper;
    }

    public QrLoginController.CreateResponse createSession(String deviceId, String deviceModel,
                                                          HttpServletRequest http) {
        String normalizedDeviceId = deviceId == null ? "" : deviceId.trim();
        if (normalizedDeviceId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_ID");
        }
        String sessionId = UUID.randomUUID().toString();
        String platform = defaultWebPlatform(clientContext.platform(http));
        String model = clientContext.deviceModel(http, deviceModel);
        int ttl = props.ttlSeconds();
        store.create(
            sessionId,
            normalizedDeviceId,
            model,
            platform,
            clientContext.ip(http),
            clientContext.userAgent(http),
            clientContext.version(http),
            ttl);
        return new QrLoginController.CreateResponse(sessionId, buildQrPayload(sessionId), ttl);
    }

    public QrLoginController.PollResponse poll(String sessionId) {
        var opt = store.find(sessionId);
        if (opt.isEmpty()) {
            return expiredPoll();
        }
        QrLoginSessionStore.Session session = opt.get();
        String status = session.status();
        if (status == null || status.isBlank()) {
            return expiredPoll();
        }
        return switch (status) {
            case QrLoginSessionStore.STATUS_PENDING,
                 QrLoginSessionStore.STATUS_SCANNED,
                 QrLoginSessionStore.STATUS_CANCELLED,
                 QrLoginSessionStore.STATUS_EXPIRED ->
                new QrLoginController.PollResponse(status, null, null, null, null, null);
            case QrLoginSessionStore.STATUS_CONFIRMED -> {
                if (session.token() == null || session.token().isBlank()
                    || session.userId() == null || session.userId().isBlank()
                    || session.tokenExpiresIn() == null) {
                    yield expiredPoll();
                }
                yield new QrLoginController.PollResponse(
                    QrLoginSessionStore.STATUS_CONFIRMED,
                    session.token(),
                    session.userId(),
                    session.tokenExpiresIn(),
                    "OK",
                    null
                );
            }
            default -> expiredPoll();
        };
    }

    public QrLoginController.ScanResponse scan(String userId, String sessionId) {
        requireActiveUser(userId);
        var opt = store.find(sessionId);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QR_SESSION_EXPIRED");
        }
        QrLoginSessionStore.Session session = opt.get();
        String status = session.status();
        if (QrLoginSessionStore.STATUS_PENDING.equals(status)) {
            store.saveScanned(sessionId, userId);
            return new QrLoginController.ScanResponse(
                sessionId, QrLoginSessionStore.STATUS_SCANNED, props.siteLabel());
        }
        if (QrLoginSessionStore.STATUS_SCANNED.equals(status)) {
            if (userId.equals(session.scannerUserId())) {
                return new QrLoginController.ScanResponse(
                    sessionId, QrLoginSessionStore.STATUS_SCANNED, props.siteLabel());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QR_SESSION_BOUND");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "QR_SESSION_EXPIRED");
    }

    public QrLoginController.ConfirmResponse confirm(String userId, String sessionId, boolean approve,
                                                     HttpServletRequest http) {
        requireActiveUser(userId);
        var opt = store.find(sessionId);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QR_SESSION_EXPIRED");
        }
        QrLoginSessionStore.Session session = opt.get();
        if (!QrLoginSessionStore.STATUS_SCANNED.equals(session.status())) {
            if (QrLoginSessionStore.STATUS_EXPIRED.equals(session.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "QR_SESSION_EXPIRED");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QR_SESSION_INVALID_STATUS");
        }
        if (session.scannerUserId() == null || !userId.equals(session.scannerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "QR_SCANNER_MISMATCH");
        }

        if (!approve) {
            store.saveCancelled(sessionId);
            return new QrLoginController.ConfirmResponse(
                sessionId, QrLoginSessionStore.STATUS_CANCELLED);
        }

        String webDeviceId = session.webDeviceId() == null ? "" : session.webDeviceId();
        String platform = defaultWebPlatform(session.webPlatform());
        String model = session.webDeviceModel();

        if (adminDevicesService.isDeviceBanned(webDeviceId)) {
            loginLogService.recordWebQrLogin(
                userId, webDeviceId, platform, session.webIp(), session.webUserAgent(),
                session.webClientVersion(), false, "DEVICE_BANNED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEVICE_BANNED");
        }

        deviceService.trust(userId, webDeviceId, platform, model);

        UserSessionService.SessionIssueResult issued = issueWebToken(userId, webDeviceId, platform);
        store.saveConfirmed(sessionId, userId, issued.token(), issued.expiresIn());
        loginLogService.recordWebQrLogin(
            userId, webDeviceId, platform, session.webIp(), session.webUserAgent(),
            session.webClientVersion(), true, null);
        return new QrLoginController.ConfirmResponse(
            sessionId, QrLoginSessionStore.STATUS_CONFIRMED);
    }

    private UserSessionService.SessionIssueResult issueWebToken(String userId, String webDeviceId,
                                                                String platform) {
        UserSessionService.SessionIssueResult session =
            sessionService.createSession(userId, webDeviceId, platform);
        presenceService.loginActive(userId);
        presenceService.deviceHeartbeat(userId, webDeviceId);
        return session;
    }

    private User requireActiveUser(String userId) {
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        return u;
    }

    /** 创建/确认时 platform 为空则记为 web，便于 /me/devices 展示与同平台会话互踢。 */
    static String defaultWebPlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return DEFAULT_WEB_PLATFORM;
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private String buildQrPayload(String sessionId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "web_login");
            payload.put("sessionId", sessionId);
            payload.put("v", 1);
            return jsonMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "QR_PAYLOAD_ENCODE_FAILED");
        }
    }

    private static QrLoginController.PollResponse expiredPoll() {
        return new QrLoginController.PollResponse(
            QrLoginSessionStore.STATUS_EXPIRED, null, null, null, null, null);
    }
}
