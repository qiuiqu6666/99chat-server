package com.chat99.server.user;

import com.chat99.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户当前位置上报（需 JWT）。与心跳分离，独立限流。
 */
@RestController
public class MeLocationController {

    private final UserLocationService locationService;
    private final ClientContext clientContext;

    public MeLocationController(UserLocationService locationService, ClientContext clientContext) {
        this.locationService = locationService;
        this.clientContext = clientContext;
    }

    @PutMapping("/me/location")
    public Map<String, Object> putLocation(Authentication auth,
                                           HttpServletRequest http,
                                           @RequestBody LocationBody body) {
        String userId = (String) auth.getPrincipal();
        UserLocationService.UploadResult result = locationService.upsert(
            userId,
            new UserLocationService.LocationUpdate(
                body.latitude(),
                body.longitude(),
                body.accuracy(),
                body.altitude(),
                body.heading(),
                body.speed(),
                body.collectedAt(),
                body.source(),
                body.deviceId()),
            clientContext.ip(http));
        return Map.of(
            "accepted", result.accepted(),
            "nextUploadAfterMs", result.nextUploadAfterMs());
    }

    public record LocationBody(
        Double latitude,
        Double longitude,
        Double accuracy,
        Double altitude,
        Double heading,
        Double speed,
        Long collectedAt,
        String source,
        String deviceId
    ) {}
}
