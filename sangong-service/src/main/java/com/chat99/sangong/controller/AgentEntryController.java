package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongAgentChatBinding;
import com.chat99.sangong.repository.AgentChatBindingRepository;
import com.chat99.sangong.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 前端进入 IM 群时调用；由登录用户和当前群聊共同决定代理入口及租户。 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentEntryController {
    private final JwtService jwtService;
    private final AgentChatBindingRepository bindings;

    public AgentEntryController(JwtService jwtService, AgentChatBindingRepository bindings) {
        this.jwtService = jwtService;
        this.bindings = bindings;
    }

    @GetMapping("/entry-context")
    public ResponseEntity<Map<String, Object>> entryContext(
        @RequestParam(name = "imGroupId") String imGroupId, HttpServletRequest request) {
        String userId;
        try {
            userId = authenticatedUserId(request);
        } catch (IllegalArgumentException | JwtException e) {
            return ResponseEntity.status(401).body(error("UNAUTHORIZED", "需要有效的主服务登录 Token"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(500).body(error("SERVER_MISCONFIG", e.getMessage()));
        }
        if (imGroupId == null || imGroupId.isBlank()) {
            return ResponseEntity.badRequest().body(error("IM_GROUP_REQUIRED", "imGroupId 必填"));
        }
        SangongAgentChatBinding binding = bindings.findActive(userId, imGroupId.trim()).orElse(null);
        if (binding == null) return ResponseEntity.ok(hidden());
        Map<String, Object> agent = bindings.findRebateAgentSummary(binding.getTenantId(), userId).orElse(null);
        if (agent == null) return ResponseEntity.ok(hidden());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("showAgentEntry", true);
        out.put("tenantId", binding.getTenantId());
        out.put("agentImUserId", userId);
        out.put("agentImGroupId", binding.getAgentImGroupId());
        out.put("agent", agent);
        out.put("userId", agent.get("userId"));
        out.put("rebatePct", agent.get("rebatePct"));
        out.put("balance", agent.get("balance"));
        out.put("playerCount", agent.get("playerCount"));
        out.put("directPlayerCount", agent.get("directPlayerCount"));
        out.put("pendingAgentRebate", agent.get("pendingAgentRebate"));
        return ResponseEntity.ok(out);
    }

    private String authenticatedUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new IllegalArgumentException("TOKEN_REQUIRED");
        Claims claims = jwtService.parse(header.substring(7));
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("TOKEN_SUBJECT_REQUIRED");
        return subject.trim();
    }

    private static Map<String, Object> hidden() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("showAgentEntry", false);
        return out;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("code", code);
        out.put("message", message == null ? "" : message);
        return out;
    }
}
