package com.chat99.server.platform;

import com.chat99.server.platform.dto.PlatformContactResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** App 端平台公开接口(走统一响应信封)。 */
@RestController
public class PlatformAppController {

    private final PlatformContactService contactService;
    private final PlatformConfigService configService;
    private final SplashService splashService;

    public PlatformAppController(PlatformContactService contactService,
                                 PlatformConfigService configService,
                                 SplashService splashService) {
        this.contactService = contactService;
        this.configService = configService;
        this.splashService = splashService;
    }

    @GetMapping("/platform/contact")
    public PlatformContactResponse contact(HttpServletRequest request,
                                           @RequestParam(required = false) String deviceId,
                                           @RequestParam(required = false) String appVersion,
                                           @RequestParam(required = false) Integer appVersionCode,
                                           @RequestParam(required = false) String appChannel) {
        return contactService.contact(request, deviceId, appVersion, appVersionCode, appChannel);
    }

    @GetMapping("/platform/customer-service")
    public PlatformController.CustomerServiceInfo customerService() {
        return new PlatformController.CustomerServiceInfo(configService.getCustomerServiceUrl());
    }

    @GetMapping("/platform/splash")
    public SplashService.SplashResponse splash(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false) String channel) {
        return splashService.resolve(platform, appVersion, channel);
    }
}