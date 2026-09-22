package com.chat99.sangong.controller;

import com.chat99.sangong.common.BusinessException;
import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongAgentBalance;
import com.chat99.sangong.domain.SangongAgentChatBinding;
import com.chat99.sangong.domain.SangongAgentLedger;
import com.chat99.sangong.domain.SangongTenantAgentGroup;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.AgentBalanceRepository;
import com.chat99.sangong.repository.AgentChatBindingRepository;
import com.chat99.sangong.repository.AgentLedgerRepository;
import com.chat99.sangong.repository.TenantAgentGroupRepository;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.service.AgentPrivilegeService;
import com.chat99.sangong.service.AgentTransferService;
import com.chat99.sangong.service.BetService;
import com.chat99.sangong.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代理分组相关路由（/api/v1/admin/agent/**）。
 *
 * 走法 B（每租户代理实例）：
 *   - sangong_tenant_agent_groups 是真正承载代理关系的表
 *   - 当前账号 = sangong_tenants_agent_groups.agent_im_user_id（按 X-Tenant-Id 限定）
 *   - 同一个 IM 用户可以在多个租户代理（每个 (tenant_id, group_id) 一份实例）
 *   - 群主通过 PUT /api/v1/admin/user-groups/{id} 配置本租户代理（自动绑到当前 tenant）
 *
 * 鉴权：复用 GamePrivilegeFilter（admin_user_id），再加 AgentPrivilegeService.assertIsAgentOf 闸口。
 */
@RestController
@RequestMapping("/api/v1/admin/agent")
public class AgentController {
    private final AgentPrivilegeService agentAuth;
    private final AgentTransferService transferService;
    private final AgentBalanceRepository agentBalanceRepo;
    private final AgentChatBindingRepository chatBindingRepo;
    private final AgentLedgerRepository agentLedgerRepo;
    private final TenantAgentGroupRepository tagRepo;
    private final UserRepository userRepo;
    private final BetService betService;

    public AgentController(AgentPrivilegeService agentAuth,
                           AgentTransferService transferService,
                           AgentBalanceRepository agentBalanceRepo,
                           AgentChatBindingRepository chatBindingRepo,
                           AgentLedgerRepository agentLedgerRepo,
                           TenantAgentGroupRepository tagRepo,
                           UserRepository userRepo,
                           BetService betService) {
        this.agentAuth = agentAuth;
        this.transferService = transferService;
        this.agentBalanceRepo = agentBalanceRepo;
        this.chatBindingRepo = chatBindingRepo;
        this.agentLedgerRepo = agentLedgerRepo;
        this.tagRepo = tagRepo;
        this.userRepo = userRepo;
        this.betService = betService;
    }

    /** 管理员在当前租户为代理用户绑定一个专用 IM 群聊。 */
    @PutMapping("/chat-binding")
    public ResponseEntity<Map<String, Object>> saveChatBinding(@RequestBody(required = false) Map<String, Object> body) {
        String agentImUserId = Req.str(body, "agentImUserId", "").trim();
        String agentImGroupId = Req.str(body, "agentImGroupId", "").trim();
        boolean active = !Req.has(body, "isActive") || Boolean.parseBoolean(Req.str(body, "isActive", "true"));
        if (agentImUserId.isEmpty() || agentImGroupId.isEmpty()) {
            return ResponseEntity.badRequest().body(error(400, "INVALID_REQUEST",
                "agentImUserId / agentImGroupId 必填"));
        }
        try {
            SangongAgentChatBinding binding = chatBindingRepo.upsert(
                TenantContext.require(), agentImUserId, agentImGroupId, active);
            return ResponseEntity.ok(chatBindingPayload(binding));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(error(409, "AGENT_CHAT_ALREADY_BOUND",
                "该用户与群聊的组合已绑定到其他租户"));
        }
    }

    /** 查询当前租户某个代理用户的 IM 群聊绑定。 */
    @GetMapping("/chat-binding")
    public ResponseEntity<Map<String, Object>> getChatBinding(@RequestParam("agentImUserId") String agentImUserId) {
        return chatBindingRepo.findByTenantAndUser(TenantContext.require(), agentImUserId.trim())
            .map(binding -> ResponseEntity.ok(chatBindingPayload(binding)))
            .orElseGet(() -> ResponseEntity.status(404).body(
                error(404, "AGENT_CHAT_BINDING_NOT_FOUND", "当前租户未绑定代理群聊")));
    }

    /** 解除当前租户某个代理用户的 IM 群聊绑定。 */
    @DeleteMapping("/chat-binding")
    public Map<String, Object> deleteChatBinding(@RequestParam("agentImUserId") String agentImUserId) {
        chatBindingRepo.delete(TenantContext.require(), agentImUserId.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    /**
     * 当前账号作为代理的所有代理分组列表（限当前租户）。
     * 同一个 IM 用户在当前租户下可以代理多个 group_id（多分组）。
     * 如果当前租户下没有代理分组 → groups: []。
     * 前端按当前 X-Tenant-Id 选租户、再根据这个列表选具体 group_id 发起操作。
     */
    @GetMapping("/my-group")
    public Map<String, Object> myGroup(HttpServletRequest request) {
        String mainUserId = AgentPrivilegeService.adminUserId(request);
        if (mainUserId == null) return error(403, "AGENT_REQUIRED", "未识别代理账号");

        List<SangongTenantAgentGroup> tags = agentAuth.findMyAgentGroups(mainUserId);
        List<Map<String, Object>> groupList = new ArrayList<>();
        for (SangongTenantAgentGroup t : tags) {
            groupList.add(tenantAgentGroupPayload(t));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", TenantContext.require());
        out.put("groups", groupList);
        // 兼容老字段:如果只有一条,顺便在顶层返回它,前端可减少一次判空
        if (groupList.size() == 1) {
            out.put("group", groupList.get(0));
        } else if (groupList.isEmpty()) {
            out.put("code", "AGENT_GROUP_NOT_FOUND");
            out.put("message", "当前账号在本租户未绑定任何代理分组");
        }
        return out;
    }

    /** 代理余额 + 最近 N 条 ledger（默认当前租户第一条 active 分组；?groupId=X 显式指定）。 */
    @GetMapping("/my-balance")
    public Map<String, Object> myBalance(@RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
                                          @RequestParam(name = "groupId", required = false) Long groupIdParam,
                                          HttpServletRequest request) {
        long groupId = resolveAgentGroupId(request, groupIdParam);
        SangongAgentBalance bal = agentBalanceRepo.findByGroup(groupId).orElse(null);
        List<SangongAgentLedger> ledger = agentLedgerRepo.listByGroup(groupId, Math.max(1, Math.min(500, limit)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("groupId", groupId);
        out.put("balance", bal == null ? 0L : bal.getBalance());
        out.put("updatedAt", bal == null ? null : bal.getUpdatedAt());
        List<Map<String, Object>> ledgerOut = new ArrayList<>();
        for (SangongAgentLedger l : ledger) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("type", l.getType());
            row.put("amount", l.getAmount());
            row.put("balanceAfter", l.getBalanceAfter());
            row.put("refType", l.getRefType());
            row.put("refId", l.getRefId());
            row.put("note", l.getNote());
            row.put("createdAt", l.getCreatedAt());
            ledgerOut.add(row);
        }
        out.put("ledger", ledgerOut);
        return out;
    }

    /** 名下玩家列表（默认当前租户第一条 active 分组；?groupId=X 显式指定）。 */
    @GetMapping("/my-players")
    public Map<String, Object> myPlayers(@RequestParam(name = "groupId", required = false) Long groupIdParam,
                                          HttpServletRequest request) {
        long groupId = resolveAgentGroupId(request, groupIdParam);
        List<SangongUser> players = userRepo.listByGroup(groupId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SangongUser u : players) {
            out.add(playerPayload(u));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("groupId", groupId);
        r.put("players", out);
        return r;
    }

    /**
     * 代理调整名下玩家的 player_rebate_pct（必须 ≤ agent.max_rebate_pct）。
     * 校验走 sangong_tenant_agent_groups.max_rebate_pct。
     */
    @PutMapping("/players/{userId}/rebate")
    public ResponseEntity<Map<String, Object>> setPlayerRebate(@PathVariable long userId,
                                                                @RequestParam(name = "groupId", required = false) Long groupIdParam,
                                                                @RequestBody(required = false) Map<String, Object> body,
                                                                HttpServletRequest request) {
        long groupId = resolveAgentGroupId(request, groupIdParam);
        boolean per10000 = Req.has(body, "playerRebatePer10000");
        double pct = parsePct(Req.str(body, per10000 ? "playerRebatePer10000" : "playerRebatePct", "0"));
        if (per10000) pct = pct / 100.0d;
        if (pct < 0) return ResponseEntity.status(422).body(error(422, "INVALID_PCT", "playerRebatePct 必须 ≥ 0"));

        SangongTenantAgentGroup tag = tagRepo.findByTenantAndGroup(TenantContext.require(), groupId).orElse(null);
        if (tag == null) {
            return ResponseEntity.status(404).body(error(404, "AGENT_GROUP_NOT_FOUND", "代理分组不存在"));
        }
        double maxPct = tag.getMaxRebatePct();
        if (pct > maxPct) {
            return ResponseEntity.status(422).body(error(422, "REBATE_PCT_TOO_HIGH",
                "playerRebatePct=" + pct + " 超过代理上限 " + maxPct));
        }

        SangongUser u = userRepo.findById(userId).orElse(null);
        if (u == null) return ResponseEntity.status(404).body(error(404, "USER_NOT_FOUND", "用户不存在"));
        if (u.getGroupId() == null || u.getGroupId() != groupId) {
            return ResponseEntity.status(403).body(error(403, "CROSS_GROUP_FORBIDDEN", "玩家不在你名下"));
        }
        userRepo.updatePlayerRebatePct(userId, pct);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("userId", userId);
        r.put("playerRebatePct", pct);
        r.put("playerRebatePer10000", Math.round(pct * 100.0d));
        return ResponseEntity.ok(r);
    }

    /** 代理 → 玩家 划转（?groupId=X 显式指定；不传则默认当前租户第一条 active）。 */
    @PostMapping("/transfer-to-player")
    public ResponseEntity<Map<String, Object>> transferToPlayer(@RequestBody(required = false) Map<String, Object> body,
                                                                  @RequestParam(name = "groupId", required = false) Long groupIdParam,
                                                                  HttpServletRequest request) {
        long groupId = resolveAgentGroupId(request, groupIdParam);
        long toUserId = Req.lng(body, "toUserId", 0);
        long amount = Req.lng(body, "amount", 0);
        String note = Req.str(body, "note", "");
        if (toUserId <= 0 || amount <= 0) {
            return ResponseEntity.status(422).body(error(422, "INVALID_REQUEST", "toUserId / amount 必填"));
        }
        String agent = AgentPrivilegeService.adminUserId(request);
        try {
            Map<String, Object> r = transferService.agentToPlayer(agent, groupId, toUserId, amount, agent, note);
            r.put("ok", true);
            return ResponseEntity.ok(r);
        } catch (AgentTransferService.InsufficientAgentBalanceException e) {
            return ResponseEntity.status(422).body(error(422, "INSUFFICIENT_AGENT_BALANCE",
                "代理余额不足: " + e.getCurrentBalance()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(error(403, codeForState(e.getMessage()), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(error(400, "INVALID_INPUT", e.getMessage()));
        }
    }

    /** 玩家 → 代理 划转（?groupId=X 显式指定）。 */
    @PostMapping("/transfer-from-player")
    public ResponseEntity<Map<String, Object>> transferFromPlayer(@RequestBody(required = false) Map<String, Object> body,
                                                                    @RequestParam(name = "groupId", required = false) Long groupIdParam,
                                                                    HttpServletRequest request) {
        long groupId = resolveAgentGroupId(request, groupIdParam);
        long fromUserId = Req.lng(body, "fromUserId", 0);
        long amount = Req.lng(body, "amount", 0);
        String note = Req.str(body, "note", "");
        if (fromUserId <= 0 || amount <= 0) {
            return ResponseEntity.status(422).body(error(422, "INVALID_REQUEST", "fromUserId / amount 必填"));
        }
        String agent = AgentPrivilegeService.adminUserId(request);
        try {
            Map<String, Object> r = transferService.playerToAgent(agent, groupId, fromUserId, amount, agent, note);
            r.put("ok", true);
            return ResponseEntity.ok(r);
        } catch (InsufficientBalanceException e) {
            return ResponseEntity.status(422).body(error(422, "INSUFFICIENT_BALANCE", "玩家余额不足"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(error(403, codeForState(e.getMessage()), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(error(400, "INVALID_INPUT", e.getMessage()));
        }
    }

    /** 划转历史（最近 N 条；?groupId=X 显式指定）。 */
    @GetMapping("/transfer-history")
    public Map<String, Object> transferHistory(@RequestParam(name = "limit", required = false, defaultValue = "100") int limit,
                                                @RequestParam(name = "groupId", required = false) Long groupIdParam,
                                                HttpServletRequest request) {
        long groupId = resolveAgentGroupId(request, groupIdParam);
        List<SangongAgentLedger> rows = agentLedgerRepo.listByGroup(groupId, Math.max(1, Math.min(500, limit)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SangongAgentLedger l : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("type", l.getType());
            row.put("amount", l.getAmount());
            row.put("balanceAfter", l.getBalanceAfter());
            row.put("refType", l.getRefType());
            row.put("refId", l.getRefId());
            row.put("note", l.getNote());
            row.put("createdAt", l.getCreatedAt());
            out.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("groupId", groupId);
        r.put("rows", out);
        return r;
    }

    /**
     * 代理代录下注（限本代理名下玩家）。
     * 闸口：当前账号必须是玩家所在分组的代理（按当前租户查 sangong_tenant_agent_groups）。
     */
    @PostMapping("/proxy-bet")
    public ResponseEntity<Map<String, Object>> proxyBet(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        long userId = Req.lng(body, "userId", 0);
        int door = Req.intval(body, "door", 0);
        long amount = Req.lng(body, "amount", 0);
        if (userId <= 0 || door < 1 || amount <= 0) {
            return ResponseEntity.status(422).body(error(422, "INVALID_REQUEST", "userId / door / amount 必填"));
        }
        SangongUser player = userRepo.findById(userId).orElse(null);
        if (player == null) return ResponseEntity.status(404).body(error(404, "USER_NOT_FOUND", "用户不存在"));
        if (player.getGroupId() == null) {
            return ResponseEntity.status(403).body(error(403, "PLAYER_NOT_IN_GROUP", "玩家未绑定代理分组"));
        }
        // 闸口：当前账号必须是玩家所在分组的代理（按当前租户）
        try {
            agentAuth.assertCanProxyBet(request, player.getGroupId());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(error(403, codeForState(e.getMessage()), e.getMessage()));
        }

        Map<String, Object> result;
        try {
            result = betService.placeBet(player, door, amount, true);
        } catch (InsufficientBalanceException e) {
            return ResponseEntity.status(422).body(error(422, "INSUFFICIENT_BALANCE", "用户余额不足，无法代录"));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(error(e.getHttpStatus(), e.getCode(), e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(422).body(error(422, "BET_REJECTED", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("userId", userId);
        out.put("door", result.get("door"));
        out.put("amount", result.get("amount"));
        out.put("doorTotal", result.get("doorTotal"));
        out.put("balance", result.get("balance"));
        return ResponseEntity.status(201).body(out);
    }

    // ===== 内部 helper =====

    /**
     * 解析操作目标的 group_id：
     *   - 如果显式传了 groupId query 参数，校验它属于当前账号在当前租户的代理分组 → 不属于抛 NOT_AGENT_OF_GROUP
     *   - 否则 fallback 到当前租户第一条 active 分组
     */
    private long resolveAgentGroupId(HttpServletRequest request, Long groupIdParam) {
        String mainUserId = AgentPrivilegeService.adminUserId(request);
        if (mainUserId == null) {
            throw new IllegalStateException("AGENT_REQUIRED");
        }
        if (groupIdParam != null && groupIdParam > 0) {
            // 显式指定：必须在本租户是当前账号的代理
            try {
                agentAuth.assertIsAgentOf(mainUserId, groupIdParam);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("NOT_AGENT_OF_GROUP");
            }
            return groupIdParam;
        }
        return agentAuth.findMyAgentGroup(mainUserId)
            .map(SangongTenantAgentGroup::getGroupId)
            .orElseThrow(() -> new IllegalStateException("AGENT_GROUP_NOT_FOUND"));
    }

    /**
     * 保留旧 API：解析当前账号在当前租户的代理主分组 group_id。
     * 新代码应优先用 resolveAgentGroupId。
     */
    @Deprecated
    private long requireAgentGroupId(HttpServletRequest request) {
        return resolveAgentGroupId(request, null);
    }

    private Map<String, Object> tenantAgentGroupPayload(SangongTenantAgentGroup tag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", tag.getId());
        out.put("tenantId", tag.getTenantId());
        out.put("groupId", tag.getGroupId());
        out.put("agentImUserId", tag.getAgentImUserId());
        out.put("maxRebatePct", tag.getMaxRebatePct());
        out.put("isActive", tag.isActive());
        out.put("note", tag.getNote());
        out.put("createdAt", tag.getCreatedAt());
        out.put("updatedAt", tag.getUpdatedAt());
        return out;
    }

    private Map<String, Object> chatBindingPayload(SangongAgentChatBinding binding) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", binding.getTenantId());
        out.put("agentImUserId", binding.getAgentImUserId());
        out.put("agentImGroupId", binding.getAgentImGroupId());
        out.put("isActive", binding.isActive());
        out.put("createdAt", binding.getCreatedAt());
        out.put("updatedAt", binding.getUpdatedAt());
        return out;
    }

    private Map<String, Object> playerPayload(SangongUser u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", u.getId());
        out.put("imUserId", u.getImUserId());
        out.put("nickname", u.getNickname());
        out.put("balance", u.getBalance());
        out.put("playerRebatePct", u.getPlayerRebatePct());
        out.put("groupId", u.getGroupId());
        return out;
    }

    private static double parsePct(String s) {
        if (s == null || s.isEmpty()) return 0d;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static String codeForState(String msg) {
        if (msg == null) return "FORBIDDEN";
        if (msg.startsWith("NOT_AGENT")) return "NOT_AGENT_OF_GROUP";
        if (msg.startsWith("CROSS_GROUP")) return "CROSS_GROUP_FORBIDDEN";
        if (msg.startsWith("AGENT_GROUP_NOT_CONFIGURED")) return "AGENT_GROUP_NOT_CONFIGURED";
        if (msg.startsWith("AGENT_GROUP_INACTIVE")) return "AGENT_GROUP_INACTIVE";
        if (msg.startsWith("AGENT_GROUP_NOT_FOUND")) return "AGENT_GROUP_NOT_FOUND";
        if (msg.startsWith("AGENT_REQUIRED")) return "AGENT_REQUIRED";
        return "FORBIDDEN";
    }

    private static Map<String, Object> error(int status, String code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("code", code);
        r.put("message", message == null ? "" : message);
        return r;
    }
}
