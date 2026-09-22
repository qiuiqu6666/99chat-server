package com.chat99.server.chatattachment;

import com.chat99.server.security.UserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatAttachmentCapabilityService {

    public record ClientCapability(
        String platform,
        String appVersion,
        Integer appVersionCode,
        Integer protocolVersion,
        String deviceId,
        boolean capable
    ) {}

    private final ChatAttachmentProperties props;
    private final ChatAttachmentDeviceCapabilityRepository capabilityRepository;
    private final UserSessionService sessionService;

    public ChatAttachmentCapabilityService(ChatAttachmentProperties props,
                                           ChatAttachmentDeviceCapabilityRepository capabilityRepository,
                                           UserSessionService sessionService) {
        this.props = props;
        this.capabilityRepository = capabilityRepository;
        this.sessionService = sessionService;
    }

    public ClientCapability readAndStore(String userId, HttpServletRequest request) {
        ClientCapability cap = read(request);
        String deviceId = cap.deviceId();
        if (userId != null && deviceId != null && !deviceId.isBlank()) {
            ChatAttachmentDeviceCapability row = capabilityRepository
                .findById(new ChatAttachmentDeviceCapabilityId(userId, deviceId))
                .orElseGet(ChatAttachmentDeviceCapability::new);
            row.setUserId(userId);
            row.setDeviceId(deviceId);
            row.setPlatform(cap.platform());
            row.setAppVersion(cap.appVersion());
            row.setAppVersionCode(cap.appVersionCode());
            row.setProtocolVersion(cap.protocolVersion());
            row.setCapable(cap.capable());
            row.setLastSeenAt(Instant.now());
            capabilityRepository.save(row);
        }
        return cap;
    }

    public ClientCapability read(HttpServletRequest request) {
        String platform = header(request, "X-Client-Platform");
        if (platform != null) {
            platform = platform.trim().toLowerCase(Locale.ROOT);
        }
        String appVersion = firstNonBlank(header(request, "X-App-Version"), header(request, "X-Client-Version"));
        Integer versionCode = parseInt(header(request, "X-App-Version-Code"));
        Integer protocol = parseInt(header(request, "X-Chat-Attachment-Protocol-Version"));
        String deviceId = header(request, "X-Device-Id");
        boolean capable = isCapable(platform, appVersion, versionCode, protocol);
        return new ClientCapability(platform, appVersion, versionCode, protocol, deviceId, capable);
    }

    public boolean isCapable(String platform, String appVersion, Integer versionCode, Integer protocol) {
        if (platform == null || !props.supportedPlatforms().contains(platform.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return protocol != null && protocol >= props.requiredProtocolVersion();
    }

    public void requirePeerDevicesCapable(String userId) {
        if (!props.gray().requirePeerCapability()) {
            return;
        }
        Set<String> active = sessionService.listActiveDeviceIds(userId);
        if (active.isEmpty()) {
            return;
        }
        var rows = capabilityRepository.findByUserIdAndDeviceIdIn(userId, active);
        if (rows.size() < active.size()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
        }
        for (ChatAttachmentDeviceCapability row : rows) {
            if (!row.isCapable()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
            }
        }
    }

    private static String header(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String v = request.getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
