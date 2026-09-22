package com.chat99.sangong.controller;

import com.chat99.sangong.security.JwtService;
import com.chat99.sangong.service.TenantService;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class AdminAuthController {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantService tenants;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminAuthController(NamedParameterJdbcTemplate jdbc, TenantService tenants, JwtService jwt) {
        this.jdbc = jdbc; this.tenants = tenants; this.jwt = jwt;
    }

    @PostMapping("/api/v1/admin/auth/setup")
    public Map<String, Object> setup(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        String tenant = TenantContext.require();
        String mainUserId = mainUserId(request);
        if (!tenants.isOwner(mainUserId, tenant)) throw new IllegalStateException("只有群主可以设置后台账号");
        String username = required(body.get("username"));
        String password = required(body.get("password"));
        if (password.length() < 6) throw new IllegalArgumentException("后台密码至少 6 位");
        jdbc.update("INSERT INTO sangong_admin_accounts(tenant_id,username,password_hash,main_user_id) " +
            "VALUES(:t,:u,:p,:m) ON DUPLICATE KEY UPDATE password_hash=:p,main_user_id=:m,enabled=1",
            new MapSqlParameterSource().addValue("t", tenant).addValue("u", username)
                .addValue("p", encoder.encode(password)).addValue("m", mainUserId));
        return Map.of("ok", true, "tenantId", tenant, "username", username);
    }

    @PostMapping("/api/v1/admin/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String username = required(body.get("username"));
        String password = required(body.get("password"));
        String tenantInput = body.get("tenantId") == null ? "" : String.valueOf(body.get("tenantId")).trim();
        String sql = tenantInput.isBlank()
            ? "SELECT tenant_id,main_user_id,password_hash FROM sangong_admin_accounts WHERE username=:u AND enabled=1"
            : "SELECT tenant_id,main_user_id,password_hash FROM sangong_admin_accounts WHERE tenant_id=:t AND username=:u AND enabled=1";
        var rows = jdbc.queryForList(sql, new MapSqlParameterSource().addValue("t", tenantInput).addValue("u", username));
        if (rows.isEmpty()) throw new IllegalArgumentException("账号或密码错误");
        if (tenantInput.isBlank() && rows.size() > 1) throw new IllegalArgumentException("该账号对应多个租户，请联系群主使用唯一账号");
        Map<String, Object> row = rows.get(0);
        String tenant = String.valueOf(row.get("tenant_id"));
        if (!encoder.matches(password, String.valueOf(row.get("password_hash")))) throw new IllegalArgumentException("账号或密码错误");
        String mainUserId = String.valueOf(row.get("main_user_id"));
        if (!tenants.isTenantStaff(mainUserId, tenant)) throw new IllegalStateException("后台账号没有租户权限");
        Map<String, Object> out = new LinkedHashMap<>(); out.put("ok", true); out.put("tenantId", tenant);
        out.put("username", username); out.put("token", jwt.issue(mainUserId, tenant, username)); return out;
    }

    private String mainUserId(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new IllegalStateException("需要主服务登录 Token");
        String sub = jwt.parse(h.substring(7)).getSubject();
        if (sub == null || sub.isBlank()) throw new IllegalStateException("Token 无效");
        return sub.trim();
    }
    private static String required(Object value) { String s = value == null ? "" : String.valueOf(value).trim(); if (s.isEmpty()) throw new IllegalArgumentException("参数不能为空"); return s; }
}
