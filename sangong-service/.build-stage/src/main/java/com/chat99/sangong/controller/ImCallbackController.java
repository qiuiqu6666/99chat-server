package com.chat99.sangong.controller;

import com.chat99.sangong.service.ImMessageService;
import com.chat99.sangong.service.TenantService;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** IM 群消息回调兼容入口（优先走 Kafka）。按 groupId 绑定租户。 */
@RestController
public class ImCallbackController {
    private final ImMessageService messages;
    private final TenantService tenants;

    public ImCallbackController(ImMessageService messages, TenantService tenants) {
        this.messages = messages;
        this.tenants = tenants;
    }

    @PostMapping("/api/v1/im/callback")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> body = Req.body(payload);
        boolean isTencent = body.containsKey("CallbackCommand");
        String command = Req.str(body, "CallbackCommand", "");
        String action = Req.str(body, "action", "").toLowerCase();
        String groupId = Req.str(body, "GroupId", Req.str(body, "groupId", ""));

        var tenant = tenants.findActiveByGameGroup(groupId);
        if (tenant.isEmpty()) {
            Map<String, Object> ignored = new LinkedHashMap<>();
            ignored.put("ok", true);
            ignored.put("ignored", true);
            ignored.put("reason", "unknown_tenant");
            return ResponseEntity.ok(ignored);
        }

        final ImMessageService.CallbackResult[] box = new ImMessageService.CallbackResult[1];
        TenantContext.run(tenant.get().getTenantId(), () -> {
            if ("Group.CallbackAfterRecallMsg".equals(command) || "recall".equals(action)) {
                box[0] = messages.handleRecall(body, "Group.CallbackAfterRecallMsg".equals(command));
            } else {
                box[0] = messages.handleSend(body, isTencent);
            }
        });
        return toHttpResponse(box[0]);
    }

    private ResponseEntity<Map<String, Object>> toHttpResponse(ImMessageService.CallbackResult result) {
        if (result.isTencent()) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (result.status() >= 400) {
                out.put("ActionStatus", "FAIL");
                out.put("ErrorCode", result.status());
                out.put("ErrorInfo", String.valueOf(result.body().getOrDefault("message", "request failed")));
                return ResponseEntity.status(result.status()).body(out);
            }
            out.put("ActionStatus", "OK");
            out.put("ErrorCode", 0);
            out.put("ErrorInfo", "");
            return ResponseEntity.ok(out);
        }
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
