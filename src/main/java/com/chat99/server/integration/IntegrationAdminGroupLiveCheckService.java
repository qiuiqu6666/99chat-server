package com.chat99.server.integration;

import com.chat99.server.adminapi.AdminAccount;
import com.chat99.server.adminapi.AdminAccountRepository;
import com.chat99.server.adminapi.AdminJwtService;
import com.chat99.server.adminapi.AdminSessionService;
import io.jsonwebtoken.JwtException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationAdminGroupLiveCheckService {

    public static final String READ = "group_live.read";
    public static final String WRITE = "group_live.write";

    private final AdminJwtService adminJwtService;
    private final AdminSessionService sessionService;
    private final AdminAccountRepository accountRepository;

    public IntegrationAdminGroupLiveCheckService(
        AdminJwtService adminJwtService,
        AdminSessionService sessionService,
        AdminAccountRepository accountRepository
    ) {
        this.adminJwtService = adminJwtService;
        this.sessionService = sessionService;
        this.accountRepository = accountRepository;
    }

    public Map<String, Object> check(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        AdminJwtService.AdminTokenClaims claims;
        try {
            claims = adminJwtService.parse(authorization.substring(7).trim());
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (claims.username() == null || claims.username().isBlank()
            || !sessionService.isActive(claims.username(), claims.jti())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        AdminAccount account = accountRepository.findByUsername(claims.username())
            .filter(AdminAccount::isEnabled)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
        List<String> granted = effectiveGroupLivePermissions(parseCsv(account.getPermissionsCsv()));
        if (granted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_FORBIDDEN");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("username", claims.username());
        out.put("permissions", granted);
        return out;
    }

    static List<String> effectiveGroupLivePermissions(List<String> permissions) {
        if (permissions.contains("admin.manage") || permissions.contains("*")) {
            return List.of(READ, WRITE);
        }
        List<String> granted = new ArrayList<>();
        if (permissions.contains(READ)) {
            granted.add(READ);
        }
        if (permissions.contains(WRITE)) {
            granted.add(WRITE);
        }
        return List.copyOf(granted);
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
