/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.push;

import com.chat99.server.adminapi.AdminAccess;
import com.chat99.server.adminapi.AdminApiException;
import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.push.PushConfigController;
import com.chat99.server.push.PushConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping(value={"/api/v1/push/config"})
public class PushConfigController {
    private final PushConfigService configService;

    public PushConfigController(PushConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<ConfigResponse> getConfig(Authentication auth) {
        PushConfigController.requireSystemConfig(auth);
        return ResponseEntity.ok(new ConfigResponse(this.configService.isPushEnabled(), this.configService.isSkipWhenOnline(), this.configService.isVoipPushEnabled(), this.configService.isJpushEnabled(), PushConfigController.configured(this.configService.getJpushAppKey()), PushConfigController.configured(this.configService.getJpushMasterSecret()), this.configService.getJpushBaseUrl(), this.configService.isImCallbackEnabled(), this.configService.isChatPushEnabled(), PushConfigController.configured(this.configService.getCallbackToken()), this.configService.getAllowedSdkAppIds(), this.configService.isChatPushSkipWhenOnline(), this.configService.getSkipSenderIds(), this.configService.getMaxGroupMembersPerPush(), this.configService.getDedupTtlHours()));
    }

    @PatchMapping
    public ResponseEntity<Void> updateConfig(Authentication auth, @RequestBody UpdateRequest req) {
        PushConfigController.requireSystemConfig(auth);
        if (req == null || req.key() == null || req.value() == null) {
            return ResponseEntity.badRequest().build();
        }
        switch (req.key()) {
            case "pushEnabled": {
                this.configService.setPushEnabled(Boolean.parseBoolean(req.value()));
                break;
            }
            case "skipWhenOnline": {
                this.configService.setSkipWhenOnline(Boolean.parseBoolean(req.value()));
                break;
            }
            case "voipPushEnabled": {
                this.configService.setVoipPushEnabled(Boolean.parseBoolean(req.value()));
                break;
            }
            case "jpushEnabled": {
                this.configService.setJpushEnabled(Boolean.parseBoolean(req.value()));
                break;
            }
            case "jpushAppKey": {
                this.configService.setJpushAppKey(req.value());
                break;
            }
            case "jpushMasterSecret": {
                this.configService.setJpushMasterSecret(req.value());
                break;
            }
            case "jpushBaseUrl": {
                this.configService.setJpushBaseUrl(req.value());
                break;
            }
            case "imCallbackEnabled": {
                this.configService.setImCallbackEnabled(Boolean.parseBoolean(req.value()));
                break;
            }
            case "chatPushEnabled": {
                this.configService.setChatPushEnabled(Boolean.parseBoolean(req.value()));
                break;
            }
            case "callbackToken": {
                this.configService.setCallbackToken(req.value());
                break;
            }
            case "allowedSdkAppIds": {
                this.configService.setAllowedSdkAppIds(req.value());
                break;
            }
            case "chatPushSkipWhenOnline": {
                this.configService.setChatPushSkipWhenOnline(Boolean.parseBoolean(req.value()));
                break;
            }
            case "skipSenderIds": {
                this.configService.setSkipSenderIds(req.value());
                break;
            }
            case "maxGroupMembersPerPush": {
                this.configService.setMaxGroupMembersPerPush(Integer.parseInt(req.value()));
                break;
            }
            case "dedupTtlHours": {
                this.configService.setDedupTtlHours(Integer.parseInt(req.value()));
                break;
            }
            default: {
                return ResponseEntity.notFound().build();
            }
        }
        return ResponseEntity.ok().build();
    }

    private static void requireSystemConfig(Authentication auth) {
        AdminPrincipal principal = AdminAccess.require((Authentication)auth);
        if (!principal.hasPermission("system.config") && !principal.hasPermission("admin.manage")) {
            throw new AdminApiException(HttpStatus.FORBIDDEN, "forbidden", "forbidden");
        }
    }

    private static String configured(String value) {
        return value == null || value.isBlank() ? "" : "configured";
    }





    public record ConfigResponse(boolean pushEnabled, boolean skipWhenOnline, boolean voipPushEnabled, boolean jpushEnabled, String jpushAppKey, String jpushMasterSecret, String jpushBaseUrl, boolean imCallbackEnabled, boolean chatPushEnabled, String callbackToken, String allowedSdkAppIds, boolean chatPushSkipWhenOnline, List<String> skipSenderIds, int maxGroupMembersPerPush, int dedupTtlHours) {}

    public record UpdateRequest(String key, String value) {}
}
