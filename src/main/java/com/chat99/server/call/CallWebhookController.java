package com.chat99.server.call;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @deprecated TRTC/TUICallKit end-call webhook. New clients use {@code /webhook/livekit}.
 */
@Deprecated
@RestController
@RequestMapping("/webhook/trtc")
public class CallWebhookController {

    private final CallWebhookService webhookService;

    public CallWebhookController(CallWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/call-status")
    public CallWebhookService.TrtcCallbackResponse callStatus(
        @RequestParam(value = "sdkappid", required = false) String sdkAppIdLower,
        @RequestParam(value = "SdkAppid", required = false) String sdkAppIdCamel,
        @RequestParam(value = "command", required = false) String command,
        @RequestParam(value = "CallbackCommand", required = false) String callbackCommand,
        @RequestParam(value = "clientip", required = false) String clientIpLower,
        @RequestParam(value = "ClientIP", required = false) String clientIpCamel,
        @RequestParam(value = "optplatform", required = false) String optPlatformLower,
        @RequestParam(value = "OptPlatform", required = false) String optPlatformCamel,
        @RequestParam(value = "token", required = false) String queryToken,
        @RequestHeader(value = "X-Callback-Token", required = false) String headerToken,
        HttpServletRequest request) throws IOException {

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String token = headerToken != null && !headerToken.isBlank() ? headerToken : queryToken;
        String sdkAppId = sdkAppIdLower != null && !sdkAppIdLower.isBlank() ? sdkAppIdLower : sdkAppIdCamel;
        String cmd = callbackCommand != null && !callbackCommand.isBlank() ? callbackCommand : command;
        String clientIp = clientIpLower != null && !clientIpLower.isBlank() ? clientIpLower : clientIpCamel;
        String optPlatform = optPlatformLower != null && !optPlatformLower.isBlank()
            ? optPlatformLower : optPlatformCamel;
        return webhookService.handle(sdkAppId, cmd, clientIp, optPlatform, token, body);
    }
}
