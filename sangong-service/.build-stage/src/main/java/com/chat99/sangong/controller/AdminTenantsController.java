package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongTenant;
import com.chat99.sangong.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
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

/** 统一后台：游戏群（租户）列表、注册与账号授权（按账号隔离）。 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class AdminTenantsController {
    private final TenantService tenants;

    public AdminTenantsController(TenantService tenants) {
        this.tenants = tenants;
    }

    private static String adminUserId(HttpServletRequest request) {
        Object v = request.getAttribute("admin_user_id");
        return v == null ? null : String.valueOf(v);
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenants", tenants.listForAdmin(adminUserId(request)));
        return out;
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<?> get(@PathVariable String tenantId, HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        // 避免把 "members" 之类误当成 tenantId（正常不会，因 my-config 在别的 controller）
        ResponseEntity<?> denied = denyIfNoAccess(tenantId, request);
        if (denied != null) {
            return denied;
        }
        return tenants.find(tenantId)
            .<ResponseEntity<?>>map(t -> ResponseEntity.ok(row(t)))
            .orElseGet(AdminTenantsController::notFound);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        Map<String, Object> in = body == null ? Map.of() : body;
        try {
            SangongTenant t = tenants.create(
                adminUserId(request),
                str(in.get("name")),
                str(in.get("imGroupGameId")),
                str(in.get("imGroupAdminStatsId")),
                str(in.get("imGroupLedgerId")),
                str(in.get("imGroupWaterId")),
                str(in.get("imBotUserId")));
            return ResponseEntity.status(201).body(row(t));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping("/{tenantId}")
    public ResponseEntity<?> update(@PathVariable String tenantId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        ResponseEntity<?> denied = denyIfNoAccess(tenantId, request);
        if (denied != null) {
            return denied;
        }
        try {
            SangongTenant t = tenants.update(adminUserId(request), tenantId, body == null ? Map.of() : body);
            return ResponseEntity.ok(row(t));
        } catch (IllegalArgumentException e) {
            return notFound();
        } catch (IllegalStateException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 存量未认领的游戏群：当前特权账号认领为 owner。 */
    @PostMapping("/{tenantId}/claim")
    public ResponseEntity<?> claim(@PathVariable String tenantId, HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        try {
            tenants.claim(adminUserId(request), tenantId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return notFound();
        } catch (IllegalStateException e) {
            return forbidden(e.getMessage());
        }
    }

    @GetMapping("/{tenantId}/access")
    public ResponseEntity<?> listAccess(@PathVariable String tenantId, HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        ResponseEntity<?> denied = denyIfNoAccess(tenantId, request);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(Map.of("ok", true, "members", tenants.listAccess(tenantId)));
    }

    /** owner 授权其他特权账号管理此群，body: {imUserId, role: owner|admin}。 */
    @PostMapping("/{tenantId}/access")
    public ResponseEntity<?> grant(@PathVariable String tenantId,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        Map<String, Object> in = body == null ? Map.of() : body;
        try {
            tenants.grantAccess(adminUserId(request), tenantId,
                str(in.get("imUserId")), str(in.get("role")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return forbidden(e.getMessage());
        }
    }

    @DeleteMapping("/{tenantId}/access/{imUserId}")
    public ResponseEntity<?> revoke(@PathVariable String tenantId,
                                    @PathVariable String imUserId,
                                    HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        try {
            tenants.revokeAccess(adminUserId(request), tenantId, imUserId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 把此群设为当前账号的默认游戏群。 */
    @PutMapping("/{tenantId}/default")
    public ResponseEntity<?> setDefault(@PathVariable String tenantId, HttpServletRequest request) {
        tenantId = decodeTenantId(tenantId);
        try {
            tenants.setDefaultTenant(adminUserId(request), tenantId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalStateException e) {
            return forbidden(e.getMessage());
        }
    }

    private ResponseEntity<?> denyIfNoAccess(String tenantId, HttpServletRequest request) {
        if (tenants.find(tenantId).isEmpty()) {
            return notFound();
        }
        if (!tenants.canManage(adminUserId(request), tenantId)) {
            return forbidden("该账号无权操作此游戏群");
        }
        return null;
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of(
            "ok", false, "code", "TENANT_NOT_FOUND", "message", "游戏群租户不存在"));
    }

    private static ResponseEntity<?> forbidden(String message) {
        return ResponseEntity.status(403).body(Map.of(
            "ok", false, "code", "TENANT_ACCESS_DENIED", "message", message));
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
            "ok", false, "code", "INVALID_INPUT", "message", message));
    }

    private static Map<String, Object> row(SangongTenant t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", t.getTenantId());
        out.put("name", t.getName());
        out.put("imGroupGameId", t.getImGroupGameId());
        out.put("imGroupAdminStatsId", t.getImGroupAdminStatsId());
        out.put("imGroupLedgerId", t.getImGroupLedgerId());
        out.put("imGroupWaterId", t.getImGroupWaterId());
        out.put("imBotUserId", t.getImBotUserId());
        out.put("active", t.isActive());
        return out;
    }

    /** 路径里的 {@code @TGS#xxx} 必须 encodeURIComponent；这里再兜底解码一次。 */
    private static String decodeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return tenantId;
        }
        try {
            return java.net.URLDecoder.decode(tenantId, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return tenantId.trim();
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
