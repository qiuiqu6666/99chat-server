package com.chat99.server.platform;

import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.platform.dto.PlatformContactResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformConfigService configService;
    private final PlatformContactService contactService;
    private final SplashService splashService;

    public PlatformController(PlatformConfigService configService,
                              PlatformContactService contactService,
                              SplashService splashService) {
        this.configService = configService;
        this.contactService = contactService;
        this.splashService = splashService;
    }

    @GetMapping("/contact")
    public PlatformContactResponse contact(HttpServletRequest request,
                                           @RequestParam(required = false) String deviceId,
                                           @RequestParam(required = false) String appVersion,
                                           @RequestParam(required = false) Integer appVersionCode,
                                           @RequestParam(required = false) String appChannel) {
        return contactService.contact(request, deviceId, appVersion, appVersionCode, appChannel);
    }

    /**
     * App 启动图(公开、免登录)。无配置或过期时返回 enabled=false,客户端回退包内默认图。
     */
    @GetMapping("/splash")
    public SplashService.SplashResponse splash(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false) String channel) {
        return splashService.resolve(platform, appVersion, channel);
    }

    public record CustomerServiceInfo(String url) {}

    @GetMapping("/customer-service")
    public CustomerServiceInfo customerService() {
        return new CustomerServiceInfo(configService.getCustomerServiceUrl());
    }

    public record ContactInfo(String website, String email, String version, String build, String downloadUrl) {
        static ContactInfo from(PlatformConfigService configService) {
            return new ContactInfo(
                configService.getWebsite(),
                configService.getEmail(),
                configService.getVersion(),
                configService.getBuild(),
                configService.getDownloadUrl());
        }
    }

    public record ConfigResponse(
        String website,
        String email,
        String version,
        String build,
        String downloadUrl,
        String feedbackPrefix,
        int maxFeedbackScreenshots,
        int maxFeedbackContentLength
    ) {}

    public record UpdateRequest(String key, String value) {}

    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new ConfigResponse(
            configService.getWebsite(),
            configService.getEmail(),
            configService.getVersion(),
            configService.getBuild(),
            configService.getDownloadUrl(),
            configService.getFeedbackPrefix(),
            configService.getMaxFeedbackScreenshots(),
            configService.getMaxFeedbackContentLength()
        ));
    }

    @PatchMapping("/config")
    public ResponseEntity<Void> updateConfig(@RequestBody UpdateRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal)) {
            return ResponseEntity.status(401).build();
        }
        switch (req.key()) {
            case "website" -> configService.setWebsite(req.value());
            case "email" -> configService.setEmail(req.value());
            case "version" -> configService.setVersion(req.value());
            case "build" -> configService.setBuild(req.value());
            case "downloadUrl" -> configService.setDownloadUrl(req.value());
            case "feedbackPrefix" -> configService.setFeedbackPrefix(req.value());
            case "maxFeedbackScreenshots" -> configService.setMaxFeedbackScreenshots(Integer.parseInt(req.value()));
            case "maxFeedbackContentLength" -> configService.setMaxFeedbackContentLength(Integer.parseInt(req.value()));
            default -> {
                return ResponseEntity.notFound().build();
            }
        }
        return ResponseEntity.ok().build();
    }
}