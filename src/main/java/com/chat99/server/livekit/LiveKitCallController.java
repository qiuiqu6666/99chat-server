package com.chat99.server.livekit;

import com.chat99.server.livekit.LiveKitCallService.CallCreds;
import com.chat99.server.livekit.LiveKitCallService.InviteRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/calls/livekit")
public class LiveKitCallController {

    private final LiveKitCallService callService;

    public LiveKitCallController(LiveKitCallService callService) {
        this.callService = callService;
    }

    @PostMapping("/invite")
    public CallCreds invite(Authentication auth, @RequestBody InviteRequest body) {
        return callService.invite((String) auth.getPrincipal(), body);
    }

    @PostMapping("/accept")
    public CallCreds accept(Authentication auth, @RequestBody CallIdBody body) {
        return callService.accept((String) auth.getPrincipal(), body == null ? null : body.callId());
    }

    @PostMapping("/reject")
    public Map<String, Object> reject(Authentication auth, @RequestBody CallIdBody body) {
        return callService.reject((String) auth.getPrincipal(), body == null ? null : body.callId());
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(Authentication auth, @RequestBody CallIdBody body) {
        return callService.cancel((String) auth.getPrincipal(), body == null ? null : body.callId());
    }

    @PostMapping("/hangup")
    public Map<String, Object> hangup(Authentication auth, @RequestBody CallIdBody body) {
        return callService.hangup((String) auth.getPrincipal(), body == null ? null : body.callId());
    }

    @GetMapping("/token")
    public CallCreds token(Authentication auth, @RequestParam String callId) {
        return callService.token((String) auth.getPrincipal(), callId);
    }

    public record CallIdBody(String callId) {}
}
