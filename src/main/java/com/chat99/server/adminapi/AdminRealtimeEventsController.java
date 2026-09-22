package com.chat99.server.adminapi;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/admin/realtime")
public class AdminRealtimeEventsController {

    private final AdminRealtimeSseHub sseHub;

    public AdminRealtimeEventsController(AdminRealtimeSseHub sseHub) {
        this.sseHub = sseHub;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication auth) {
        AdminPrincipal principal = AdminAccess.require(auth);
        return sseHub.subscribe(principal.username());
    }
}
