package com.chat99.server.push;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/push")
public class PushAdminController {

    private final AdminGuard guard;
    private final PushService pushService;

    public PushAdminController(AdminGuard guard, PushService pushService) {
        this.guard = guard;
        this.pushService = pushService;
    }

    public record TestBody(
        @NotBlank String toUserId,
        @NotBlank String title,
        String body,
        Map<String, String> data) {}

    @PostMapping("/test")
    public Map<String, Object> test(@Valid @RequestBody TestBody body, HttpServletRequest http) {
        guard.check(http);
        String text = body.body() != null && !body.body().isBlank() ? body.body() : body.title();
        Map<String, String> data = body.data() == null ? Map.of() : new LinkedHashMap<>(body.data());
        PushMessage message = PushMessage.of(body.title(), text);
        for (Map.Entry<String, String> entry : data.entrySet()) {
            message = message.withData(entry.getKey(), entry.getValue());
        }
        pushService.sendToUser(body.toUserId(), message);
        return Map.of("ok", true, "enabled", pushService.enabled());
    }
}
