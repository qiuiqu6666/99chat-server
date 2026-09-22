package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.service.RebateClaimService;
import com.chat99.sangong.service.UserHierarchyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import java.time.LocalDate;
import com.chat99.sangong.service.UserTransferService;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.service.TenantService;
import com.chat99.sangong.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RebateController {
    private final RebateClaimService rebates;
    private final UserHierarchyService hierarchy;
    private final com.chat99.sangong.service.TeamReportService team;
    private final UserTransferService transfers;
    private final UserRepository users;
    private final TenantService tenants;
    private final com.chat99.sangong.service.MainUserProfileService profiles;
    private final com.chat99.sangong.service.SessionService sessions;
    public RebateController(RebateClaimService rebates, UserHierarchyService hierarchy,
                            com.chat99.sangong.service.TeamReportService team,
                            UserTransferService transfers, UserRepository users, TenantService tenants,
                            com.chat99.sangong.service.MainUserProfileService profiles,
                            com.chat99.sangong.service.SessionService sessions) {
        this.rebates = rebates; this.hierarchy = hierarchy; this.team = team; this.transfers = transfers; this.users = users; this.tenants = tenants; this.profiles = profiles; this.sessions = sessions;
    }

    @GetMapping("/api/v1/me/rebate")
    public Map<String, Object> status(HttpServletRequest request) {
        return rebates.status(user(request));
    }

    @PostMapping("/api/v1/me/rebate/claim")
    public Map<String, Object> claim(HttpServletRequest request) {
        return rebates.claimPlayerRebate(user(request));
    }

    @GetMapping("/api/v1/me/children")
    public Map<String, Object> children(HttpServletRequest request,
                                        @RequestParam(defaultValue = "false") boolean direct) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true); out.put("children", hierarchy.children(user(request).getId(), direct));
        return out;
    }

    @GetMapping("/api/v1/me/parent")
    public Map<String, Object> parent(HttpServletRequest request) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", TenantContext.require());
        out.put("userId", user(request).getId());
        out.put("parent", hierarchy.parent(user(request).getId()));
        return out;
    }

    @GetMapping("/api/v1/me/users/{userId}/parent")
    public Map<String, Object> userParent(HttpServletRequest request, @PathVariable String userId) {
        SangongUser operator = user(request);
        String tenantId = TenantContext.require();
        if (!tenants.isTenantStaff(operator.getImUserId(), tenantId)) {
            throw new IllegalStateException("只有群主或帮工可以查询指定用户的上级");
        }
        long targetId = resolveUserId(userId);
        SangongUser target = users.findByTenantAndId(tenantId, targetId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", tenantId);
        out.put("userId", target.getId());
        out.put("imUserId", target.getImUserId());
        Map<String, Object> parent = hierarchy.parent(target.getId());
        if (parent != null) {
            String parentImUserId = String.valueOf(parent.get("imUserId"));
            Map<String, String> profile = profiles.getProfiles(java.util.List.of(parentImUserId)).get(parentImUserId);
            if (profile != null && profile.get("avatarUrl") != null) {
                parent.put("avatarUrl", profile.get("avatarUrl"));
            }
        }
        out.put("parent", parent);
        return out;
    }

    @PostMapping("/api/v1/me/hierarchy/bind")
    public Map<String, Object> bind(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long parent = resolveUserId(required(body.get("parentUserId")));
        long child = resolveUserId(required(body.get("childUserId")));
        hierarchy.bindByOperator(user(request).getId(), parent, child);
        return Map.of("ok", true, "parentUserId", body.get("parentUserId"), "childUserId", body.get("childUserId"));
    }

    @PutMapping("/api/v1/me/hierarchy/{childUserId}/parent")
    public Map<String, Object> changeParent(HttpServletRequest request, @PathVariable String childUserId,
                                             @RequestBody Map<String, Object> body) {
        hierarchy.changeParent(user(request).getId(), resolveUserId(childUserId),
            resolveUserId(required(body.get("parentUserId"))));
        return Map.of("ok", true, "childUserId", childUserId, "parentUserId", body.get("parentUserId"));
    }

    @DeleteMapping("/api/v1/me/hierarchy/{childUserId}")
    public Map<String, Object> unbind(HttpServletRequest request, @PathVariable String childUserId) {
        hierarchy.unbind(user(request).getId(), resolveUserId(childUserId));
        return Map.of("ok", true);
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/v1/me/children/{userId}/rebate")
    public Map<String, Object> setRebate(HttpServletRequest request, @PathVariable String userId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("rebatePer10000");
        boolean per10000 = raw != null;
        if (raw == null) raw = body == null ? null : body.get("rebatePct");
        if (raw == null) throw new IllegalArgumentException("rebatePct 必填");
        double pct = Double.parseDouble(String.valueOf(raw));
        if (per10000) pct = pct / 100.0d;
        long targetId = resolveUserId(userId);
        SangongUser operator = user(request);
        double saved = tenants.isTenantStaff(operator.getImUserId(), TenantContext.require())
            ? hierarchy.setTenantUserRebate(operator.getId(), targetId, pct)
            : hierarchy.setPlayerRebate(operator.getId(), targetId, pct);
        return Map.of("ok", true, "userId", targetId, "imUserId", userId, "rebatePct", saved,
            "rebatePer10000", Math.round(saved * 100.0d));
    }

    @GetMapping("/api/v1/me/children/{userId}/rebate")
    public Map<String, Object> childRebate(HttpServletRequest request, @PathVariable String userId) {
        SangongUser operator = user(request);
        long targetId = resolveUserId(userId);
        String tenantId = TenantContext.require();
        boolean staff = tenants.isTenantStaff(operator.getImUserId(), tenantId);
        if (!staff) {
            boolean descendant = hierarchy.children(operator.getId(), false).stream()
                .anyMatch(row -> ((Number) row.get("user_id")).longValue() == targetId);
            if (!descendant) throw new IllegalStateException("只能查询自己的下级用户");
        }
        SangongUser target = users.findByTenantAndId(tenantId, targetId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        double pct = target.getPlayerRebatePct();
        return Map.of("ok", true, "tenantId", tenantId, "userId", target.getId(),
            "imUserId", target.getImUserId(), "rebatePct", pct,
            "rebatePer10000", Math.round(pct * 100.0d));
    }

    private long resolveUserId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return users.findByImUserId(raw)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"))
                .getId();
        }
    }

    private static String required(Object raw) {
        if (raw == null) throw new IllegalArgumentException("用户 ID 必填");
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("用户 ID 必填");
        return value;
    }

    @GetMapping("/api/v1/me/team/summary")
    public Map<String, Object> teamSummary(HttpServletRequest request,
        @RequestParam(defaultValue = "false") boolean direct,
        @RequestParam(required = false) Long sessionId,
        @RequestParam(required = false) String batchNo,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to) {
        SangongUser agent = requireRebateAgent(request);
        var session = resolveTeamSession(sessionId, batchNo);
        if (session != null) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", true);
            out.put("tenantId", TenantContext.require());
            out.put("userId", agent.getId());
            out.put("direct", direct);
            out.put("aggregation", "session");
            out.put("session", sessions.formatSession(session));
            out.putAll(team.summaryBySession(agent.getId(), direct, session.getId()));
            return out;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", TenantContext.require()); out.put("userId", agent.getId());
        out.put("direct", direct); out.put("aggregation", "session"); out.put("session", null);
        out.put("members", 0); out.put("totalBalance", 0); out.put("playerTurnover", 0);
        out.put("bankerTurnover", 0); out.put("totalTurnover", 0); out.put("totalUp", 0);
        out.put("totalDown", 0); out.put("totalProfitLoss", 0); out.put("totalRebate", 0);
        return out;
    }

    @GetMapping("/api/v1/me/team/members")
    public Map<String, Object> teamMembers(HttpServletRequest request,
        @RequestParam(defaultValue = "false") boolean direct,
        @RequestParam(required = false) Long sessionId,
        @RequestParam(required = false) String batchNo) {
        SangongUser agent = requireRebateAgent(request);
        var session = resolveTeamSession(sessionId, batchNo);
        if (session == null) {
            return Map.of("ok", true, "tenantId", TenantContext.require(), "aggregation", "session",
                "session", Map.of(), "members", List.of());
        }
        List<Map<String, Object>> members = team.membersBySession(agent.getId(), direct, session.getId());
        Map<String, Map<String, String>> profileMap = profiles.getProfiles(members.stream()
            .map(row -> String.valueOf(row.get("imUserId"))).toList());
        for (Map<String, Object> row : members) {
            Map<String, String> profile = profileMap.get(String.valueOf(row.get("imUserId")));
            if (profile != null && profile.get("avatarUrl") != null) row.put("avatarUrl", profile.get("avatarUrl"));
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", TenantContext.require()); out.put("direct", direct);
        out.put("aggregation", "session"); out.put("session", sessions.formatSession(session));
        out.put("members", members); return out;
    }

    /** 代理团队页面聚合数据：一个请求返回当前批次概览和下级明细。 */
    @GetMapping("/api/v1/me/team/dashboard")
    public Map<String, Object> teamDashboard(HttpServletRequest request,
                                              @RequestParam(defaultValue = "false") boolean direct,
                                              @RequestParam(required = false) Long sessionId,
                                              @RequestParam(required = false) String batchNo) {
        SangongUser agent = requireRebateAgent(request);
        var session = resolveTeamSession(sessionId, batchNo);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", TenantContext.require());
        out.put("direct", direct);

        Map<String, Object> agentOut = new java.util.LinkedHashMap<>();
        agentOut.put("userId", agent.getId());
        agentOut.put("imUserId", agent.getImUserId());
        agentOut.put("nickname", agent.getNickname());
        agentOut.put("rebatePct", agent.getPlayerRebatePct());
        agentOut.put("rebatePer10000", Math.round(agent.getPlayerRebatePct() * 100.0d));
        Map<String, String> agentProfile = profiles.getProfiles(List.of(agent.getImUserId())).get(agent.getImUserId());
        if (agentProfile != null) {
            if (agentProfile.get("nickname") != null && !agentProfile.get("nickname").isBlank()) {
                agentOut.put("nickname", agentProfile.get("nickname"));
            }
            if (agentProfile.get("avatarUrl") != null && !agentProfile.get("avatarUrl").isBlank()) {
                agentOut.put("avatarUrl", agentProfile.get("avatarUrl"));
            }
        }
        out.put("agent", agentOut);

        if (session == null) {
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("memberCount", 0); summary.put("directMemberCount", 0);
            summary.put("totalBalance", 0); summary.put("playerTurnover", 0); summary.put("bankerTurnover", 0);
            summary.put("totalTurnover", 0); summary.put("totalUp", 0); summary.put("totalDown", 0);
            summary.put("totalProfitLoss", 0); summary.put("totalRebate", 0); summary.put("batchRebate", 0);
            out.put("batch", null); out.put("session", null); out.put("summary", summary); out.put("members", List.of());
            return out;
        }

        List<Map<String, Object>> allMembers = team.membersBySession(agent.getId(), false, session.getId());
        List<Map<String, Object>> members = direct ? allMembers.stream()
            .filter(row -> row.get("parentUserId") instanceof Number parent && parent.longValue() == agent.getId()).toList()
            : allMembers;
        Map<String, Map<String, String>> profileMap = profiles.getProfiles(members.stream()
            .map(row -> String.valueOf(row.get("imUserId"))).toList());
        for (Map<String, Object> row : members) {
            Map<String, String> profile = profileMap.get(String.valueOf(row.get("imUserId")));
            if (profile == null) continue;
            if (profile.get("nickname") != null && !profile.get("nickname").isBlank()) row.put("nickname", profile.get("nickname"));
            if (profile.get("avatarUrl") != null && !profile.get("avatarUrl").isBlank()) row.put("avatarUrl", profile.get("avatarUrl"));
        }
        Map<String, Object> summary = new java.util.LinkedHashMap<>(team.summaryBySession(agent.getId(), direct, session.getId()));
        summary.put("memberCount", summary.get("members"));
        summary.put("directMemberCount", allMembers.stream().filter(row ->
            row.get("parentUserId") instanceof Number parent && parent.longValue() == agent.getId()).count());
        summary.put("batchRebate", summary.get("totalRebate"));
        Map<String, Object> batch = sessions.formatSession(session);
        out.put("aggregation", "session");
        out.put("batch", batch);
        out.put("session", batch);
        out.put("summary", summary);
        out.put("members", members);
        return out;
    }

    /** 当前代理查看某个下级的批次数据、下级团队汇总和直属下级。 */
    @GetMapping("/api/v1/me/team/member-dashboard")
    public Map<String, Object> memberDashboard(HttpServletRequest request,
                                                @RequestParam String imUserId,
                                                @RequestParam(required = false) Long sessionId,
                                                @RequestParam(required = false) String batchNo) {
        SangongUser agent = requireRebateAgent(request);
        SangongUser member = requireDescendant(agent, imUserId);
        var session = resolveTeamSession(sessionId, batchNo);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", TenantContext.require()); out.put("aggregation", "session");
        if (session == null) {
            out.put("member", memberPayload(member)); out.put("teamSummary", Map.of());
            out.put("directMembers", List.of()); out.put("batch", null); return out;
        }
        List<Map<String, Object>> agentMembers = team.membersBySession(agent.getId(), false, session.getId());
        Map<String, Object> memberOut = agentMembers.stream()
            .filter(row -> ((Number) row.get("userId")).longValue() == member.getId())
            .<Map<String, Object>>map(row -> new java.util.LinkedHashMap<String, Object>(row))
            .findFirst().orElseGet(() -> memberPayload(member));
        enrichProfiles(List.of(memberOut));
        memberOut.putAll(team.memberBatch(member.getId(), session.getId()));
        memberOut.putAll(team.memberRebateSummary(member.getId(), session.getId()));
        memberOut.putAll(team.latestRoundStats(member.getId(), session.getId()));

        List<Map<String, Object>> directMembers = agentMembers.stream()
            .filter(row -> row.get("parentUserId") instanceof Number parent && parent.longValue() == member.getId())
            .<Map<String, Object>>map(row -> new java.util.LinkedHashMap<String, Object>(row)).toList();
        enrichProfiles(directMembers);
        Map<String, Object> summary = new java.util.LinkedHashMap<>(team.summaryBySession(member.getId(), false, session.getId()));
        summary.put("memberCount", summary.get("members"));
        summary.put("directMemberCount", directMembers.size());
        summary.put("batchRebate", summary.get("totalRebate"));
        out.put("member", memberOut); out.put("teamSummary", summary); out.put("directMembers", directMembers);
        out.put("batch", sessions.formatSession(session)); return out;
    }

    /** 当前代理查看某个下级的按业务日期数据。 */
    @GetMapping("/api/v1/me/team/member-daily")
    public Map<String, Object> memberDaily(HttpServletRequest request, @RequestParam String imUserId,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        SangongUser agent = requireRebateAgent(request);
        SangongUser member = requireDescendant(agent, imUserId);
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate start = from == null || from.isBlank() ? end.minusDays(29) : LocalDate.parse(from);
        if (start.isAfter(end)) throw new IllegalArgumentException("from 不能晚于 to");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", TenantContext.require()); out.put("imUserId", member.getImUserId());
        out.put("from", start); out.put("to", end); out.put("days", team.teamDaily(member.getId(), start, end));
        return out;
    }

    /** 个人每日数据：本人可查自己，代理可查自己的任意层级下级。 */
    @GetMapping("/api/v1/me/member-daily")
    public Map<String, Object> personalDaily(HttpServletRequest request, @RequestParam String imUserId,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String batchNo) {
        SangongUser requester = user(request);
        if (imUserId == null || imUserId.isBlank()) throw new IllegalArgumentException("imUserId 必填");
        SangongUser target = users.findByImUserId(imUserId.trim())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (target.getId() != requester.getId()) {
            SangongUser agent = requireRebateAgent(request);
            if (!team.isDescendant(agent.getId(), target.getId())) {
                throw new IllegalStateException("只能查看自己的下级用户");
            }
        }
        if (batchNo != null && !batchNo.isBlank()) {
            var batch = sessions.findBatch(batchNo.trim());
            Map<String, Object> item = new java.util.LinkedHashMap<>(team.memberBatch(target.getId(), batch.getId()));
            item.put("businessDate", batch.getBusinessDate());
            item.put("batchNo", batch.getBatchNo());
            item.put("batchStatus", batch.getStatus());
            item.put("endOfBatchBalance", item.get("balance"));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", true); out.put("tenantId", TenantContext.require()); out.put("imUserId", target.getImUserId());
            out.put("aggregation", "session"); out.put("batch", sessions.formatSession(batch)); out.put("days", List.of(item));
            return out;
        }
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate start = from == null || from.isBlank() ? end.minusDays(29) : LocalDate.parse(from);
        if (start.isAfter(end)) throw new IllegalArgumentException("from 不能晚于 to");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", TenantContext.require()); out.put("imUserId", target.getImUserId());
        out.put("from", start); out.put("to", end); out.put("days", team.memberDaily(target.getId(), start, end));
        return out;
    }

    private SangongUser requireDescendant(SangongUser agent, String imUserId) {
        if (imUserId == null || imUserId.isBlank()) throw new IllegalArgumentException("imUserId 必填");
        SangongUser target = users.findByImUserId(imUserId.trim())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (target.getId() != agent.getId() && !team.isDescendant(agent.getId(), target.getId())) {
            throw new IllegalStateException("只能查看本人或自己的下级团队");
        }
        return target;
    }

    private void enrichProfiles(List<Map<String, Object>> rows) {
        Map<String, Map<String, String>> profileMap = profiles.getProfiles(rows.stream()
            .map(row -> String.valueOf(row.get("imUserId"))).toList());
        for (Map<String, Object> row : rows) {
            Map<String, String> profile = profileMap.get(String.valueOf(row.get("imUserId")));
            if (profile == null) continue;
            if (profile.get("nickname") != null && !profile.get("nickname").isBlank()) row.put("nickname", profile.get("nickname"));
            if (profile.get("avatarUrl") != null && !profile.get("avatarUrl").isBlank()) row.put("avatarUrl", profile.get("avatarUrl"));
        }
    }

    private static Map<String, Object> memberPayload(SangongUser user) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("userId", user.getId()); out.put("imUserId", user.getImUserId()); out.put("nickname", user.getNickname());
        out.put("balance", user.getBalance()); out.put("rebatePct", user.getPlayerRebatePct());
        out.put("rebatePer10000", Math.round(user.getPlayerRebatePct() * 100.0d)); return out;
    }

    private com.chat99.sangong.domain.SangongSession resolveTeamSession(Long sessionId, String batchNo) {
        if (batchNo != null && !batchNo.isBlank()) return sessions.findBatch(batchNo.trim());
        if (sessionId != null) return sessions.findBatch(sessionId);
        var running = sessions.getRunning();
        return running != null ? running : sessions.getLatest();
    }

    private static SangongUser requireRebateAgent(HttpServletRequest request) {
        SangongUser current = user(request);
        if (current.getPlayerRebatePct() <= 0) throw new IllegalStateException("AGENT_REBATE_REQUIRED");
        return current;
    }

    @PostMapping("/api/v1/me/transfer-to-child")
    public Map<String, Object> transfer(HttpServletRequest request,
        @RequestBody(required = false) Map<String, Object> body) {
        String toImUserId = body == null ? "" : String.valueOf(body.getOrDefault("toImUserId", "")).trim();
        if (toImUserId.isEmpty()) throw new IllegalArgumentException("toImUserId 必填");
        SangongUser target = users.findByImUserId(toImUserId)
            .orElseThrow(() -> new IllegalArgumentException("下级用户不存在"));
        long amount = Long.parseLong(String.valueOf(body.get("amount")));
        return transfers.transferToChild(user(request).getId(), target.getId(), amount,
            body.get("note") == null ? "" : String.valueOf(body.get("note")));
    }

    private static SangongUser user(HttpServletRequest request) {
        SangongUser user = (SangongUser) request.getAttribute("auth_user");
        if (user == null) throw new IllegalStateException("UNAUTHORIZED");
        return user;
    }
}
