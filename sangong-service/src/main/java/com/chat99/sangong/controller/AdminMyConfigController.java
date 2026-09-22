package com.chat99.sangong.controller;

import com.chat99.sangong.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 特权用户「我的配置」：自填下注群 / 报表群 / 机器人号，以及基于当前绑定群的帮工管理。
 * 成员接口不把 {@code @TGS#...} 放进 URL，避免 {@code #} 被浏览器截断导致「租户不存在」。
 */
@RestController
@RequestMapping("/api/v1/admin/my-config")
public class AdminMyConfigController {
    private final TenantService tenants;

    public AdminMyConfigController(TenantService tenants) {
        this.tenants = tenants;
    }

    private static String adminUserId(HttpServletRequest request) {
        Object v = request.getAttribute("admin_user_id");
        return v == null ? null : String.valueOf(v);
    }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest request) {
        return tenants.getMyConfig(adminUserId(request));
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        try {
            return ResponseEntity.ok(tenants.saveMyConfig(
                adminUserId(request), body == null ? Map.of() : body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false, "code", "INVALID_INPUT", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of(
                "ok", false, "code", "TENANT_ACCESS_DENIED", "message", e.getMessage()));
        }
    }

    /** 列出当前绑定群的帮工成员。 */
    @GetMapping("/members")
    public ResponseEntity<?> listMembers(HttpServletRequest request) {
        try {
            String tenantId = tenants.requireConfiguredTenantId(adminUserId(request));
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "tenantId", tenantId,
                "members", tenants.listAccess(tenantId)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of(
                "ok", false, "code", "CONFIG_REQUIRED", "message", e.getMessage()));
        }
    }

    /**
     * 群主给当前绑定群加帮工。
     * body: {@code { "imUserId": "...", "role": "admin" }}
     */
    @PostMapping("/members")
    public ResponseEntity<?> addMember(@RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        Map<String, Object> in = body == null ? Map.of() : body;
        try {
            String tenantId = tenants.requireConfiguredTenantId(adminUserId(request));
            tenants.grantAccess(adminUserId(request), tenantId,
                str(in.get("imUserId")), str(in.get("role")));
            return ResponseEntity.ok(Map.of("ok", true, "tenantId", tenantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false, "code", "INVALID_INPUT", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String code = msg.contains("请先完成") ? "CONFIG_REQUIRED" : "TENANT_ACCESS_DENIED";
            int status = "CONFIG_REQUIRED".equals(code) ? 400 : 403;
            return ResponseEntity.status(status).body(Map.of(
                "ok", false, "code", code, "message", msg));
        }
    }

    @DeleteMapping("/members/{imUserId}")
    public ResponseEntity<?> removeMember(@PathVariable String imUserId,
                                          HttpServletRequest request) {
        try {
            String tenantId = tenants.requireConfiguredTenantId(adminUserId(request));
            tenants.revokeAccess(adminUserId(request), tenantId, imUserId);
            return ResponseEntity.ok(Map.of("ok", true, "tenantId", tenantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false, "code", "INVALID_INPUT", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String code = msg.contains("请先完成") ? "CONFIG_REQUIRED" : "TENANT_ACCESS_DENIED";
            int status = "CONFIG_REQUIRED".equals(code) ? 400 : 403;
            return ResponseEntity.status(status).body(Map.of(
                "ok", false, "code", code, "message", msg));
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
