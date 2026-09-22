package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongSession;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.LedgerRepository;
import com.chat99.sangong.repository.AgentChatBindingRepository;
import com.chat99.sangong.repository.RoundRepository;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.service.AdminSettleBillService;
import com.chat99.sangong.service.BetService;
import com.chat99.sangong.service.BetWindowMessageOrder;
import com.chat99.sangong.service.ImService;
import com.chat99.sangong.service.MainUserProfileService;
import com.chat99.sangong.service.GameSettingsService;
import com.chat99.sangong.service.UserHierarchyService;
import com.chat99.sangong.service.TeamReportService;
import com.chat99.sangong.tenant.TenantContext;
import java.time.LocalDate;
import com.chat99.sangong.service.ReportImageService;
import com.chat99.sangong.service.RoundService;
import com.chat99.sangong.service.SessionService;
import com.chat99.sangong.service.SettleService;
import com.chat99.sangong.service.TrendChartService;
import com.chat99.sangong.service.UserGroupService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminUserReportController {
    private final UserRepository users;
    private final UserGroupService groups;
    private final LedgerRepository ledgers;
    private final ReportImageService reportImage;
    private final ImService im;
    private final SessionService sessions;
    private final RoundService rounds;
    private final RoundRepository roundRepo;
    private final BetService bets;
    private final SettleService settle;
    private final TrendChartService trendChart;
    private final AdminSettleBillService settleBill;
    private final BetWindowMessageOrder messageOrder;
    private final MainUserProfileService profiles;
    private final TeamReportService teamReports;
    private final NamedParameterJdbcTemplate jdbc;
    private final GameSettingsService gameSettings;
    private final UserHierarchyService hierarchy;
    private final AgentChatBindingRepository agentChatBindings;

    public AdminUserReportController(UserRepository users, UserGroupService groups,
                                     LedgerRepository ledgers, ReportImageService reportImage,
                                     ImService im, SessionService sessions, RoundService rounds,
                                     RoundRepository roundRepo, BetService bets, SettleService settle,
                                     TrendChartService trendChart, AdminSettleBillService settleBill,
                                     BetWindowMessageOrder messageOrder, MainUserProfileService profiles,
                                     TeamReportService teamReports, NamedParameterJdbcTemplate jdbc,
                                     GameSettingsService gameSettings, UserHierarchyService hierarchy,
                                     AgentChatBindingRepository agentChatBindings) {
        this.users = users;
        this.groups = groups;
        this.ledgers = ledgers;
        this.reportImage = reportImage;
        this.im = im;
        this.sessions = sessions;
        this.rounds = rounds;
        this.roundRepo = roundRepo;
        this.bets = bets;
        this.settle = settle;
        this.trendChart = trendChart;
        this.settleBill = settleBill;
        this.messageOrder = messageOrder;
        this.profiles = profiles;
        this.teamReports = teamReports;
        this.jdbc = jdbc;
        this.gameSettings = gameSettings;
        this.hierarchy = hierarchy;
        this.agentChatBindings = agentChatBindings;
    }

    /** 群主/帮工用户详情页聚合接口，替代用户、上级、返水、流水和设置五次初始化请求。 */
    @GetMapping("/user-detail")
    public ResponseEntity<Map<String, Object>> userDetail(@RequestParam("imUserId") String imUserId) {
        if (imUserId == null || imUserId.isBlank()) {
            return PlayerBetController.err(400, "IM_USER_REQUIRED", "imUserId 必填");
        }
        SangongUser user = users.findByTenantAndImUserId(TenantContext.require(), imUserId.trim()).orElse(null);
        if (user == null) return PlayerBetController.err(404, "USER_NOT_FOUND", "用户不存在");

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("t", TenantContext.require()).addValue("u", user.getId());
        Long childrenCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sangong_user_hierarchy WHERE tenant_id=:t AND parent_user_id=:u AND is_active=1",
            params, Long.class);
        Map<String, Object> userOut = new LinkedHashMap<>();
        userOut.put("userId", user.getId());
        userOut.put("imUserId", user.getImUserId());
        userOut.put("nickname", user.getNickname());
        userOut.put("balance", user.getBalance());
        userOut.put("childrenCount", childrenCount == null ? 0L : childrenCount);
        userOut.put("rebatePct", user.getPlayerRebatePct());
        userOut.put("rebatePer10000", Math.round(user.getPlayerRebatePct() * 100.0d));

        Map<String, Object> parent = hierarchy.parent(user.getId());
        List<String> profileIds = new ArrayList<>();
        profileIds.add(user.getImUserId());
        if (parent != null && parent.get("imUserId") != null) {
            profileIds.add(String.valueOf(parent.get("imUserId")));
        }
        Map<String, Map<String, String>> profileMap = profiles.getProfiles(profileIds);
        applyProfile(userOut, profileMap.get(user.getImUserId()));
        if (parent != null) {
            applyProfile(parent, profileMap.get(String.valueOf(parent.get("imUserId"))));
        }

        List<Map<String, Object>> entries = ledgers.listFlow(user.getId(), null);
        List<Map<String, Object>> betFlow = new ArrayList<>();
        List<Map<String, Object>> bankerFlow = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String type = String.valueOf(entry.getOrDefault("type", ""));
            if (type.startsWith("bet_")) betFlow.add(entry);
            if (type.startsWith("settle_banker")) bankerFlow.add(entry);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("betFlow", betFlow.size());
        counts.put("bankerFlow", bankerFlow.size());
        counts.put("ledgerFlow", entries.size());
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("betFlow", betFlow);
        flow.put("bankerFlow", bankerFlow);
        flow.put("ledgerFlow", entries);
        flow.put("counts", counts);
        // 保留现有 user-flow 的字段，前端迁移期间可直接兼容。
        flow.put("entries", entries);
        flow.put("count", entries.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", TenantContext.require());
        out.put("user", userOut);
        out.put("parent", parent);
        out.put("flow", flow);
        out.put("settings", gameSettings.all());
        var chatBinding = agentChatBindings.findByTenantAndUser(TenantContext.require(), user.getImUserId())
            .orElse(null);
        if (chatBinding == null) {
            out.put("agentImGroupId", null);
            out.put("agentChatBinding", null);
        } else {
            Map<String, Object> bindingOut = new LinkedHashMap<>();
            bindingOut.put("agentImUserId", chatBinding.getAgentImUserId());
            bindingOut.put("agentImGroupId", chatBinding.getAgentImGroupId());
            bindingOut.put("isActive", chatBinding.isActive());
            out.put("agentImGroupId", chatBinding.getAgentImGroupId());
            out.put("agentChatBinding", bindingOut);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/users")
    public Map<String, Object> index(@RequestParam(value = "groupId", required = false) String groupId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SangongUser user : users.listAll()) {
            if ("0".equals(groupId) || "null".equals(groupId)) {
                if (user.getGroupId() != null) continue;
            } else if (groupId != null && !groupId.isEmpty()) {
                try {
                    if (user.getGroupId() == null || user.getGroupId() != Long.parseLong(groupId)) continue;
                } catch (NumberFormatException ignored) {
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", user.getId());
            row.put("imUserId", user.getImUserId());
            row.put("nickname", user.getNickname());
            row.put("balance", user.getBalance());
            MapSqlParameterSource userParams = new MapSqlParameterSource()
                .addValue("t", TenantContext.require()).addValue("u", user.getId());
            Long childrenCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sangong_user_hierarchy WHERE tenant_id=:t AND parent_user_id=:u AND is_active=1",
                userParams, Long.class);
            Double rebatePct = jdbc.queryForObject(
                "SELECT COALESCE(player_rebate_pct,0) FROM sangong_users WHERE tenant_id=:t AND id=:u",
                userParams, Double.class);
            row.put("childrenCount", childrenCount == null ? 0L : childrenCount);
            double rebate = rebatePct == null ? 0d : rebatePct;
            row.put("rebatePct", rebate);
            row.put("rebatePer10000", Math.round(rebate * 100.0d));
            row.put("group", groups.formatUserGroup(user.getGroupId()));
            rows.add(row);
        }
        List<String> imUserIds = rows.stream()
            .map(row -> String.valueOf(row.get("imUserId")))
            .filter(id -> !id.isBlank() && !"null".equalsIgnoreCase(id))
            .toList();
        Map<String, Map<String, String>> profileMap = profiles.getProfiles(imUserIds);
        for (Map<String, Object> row : rows) {
            Map<String, String> profile = profileMap.get(String.valueOf(row.get("imUserId")));
            if (profile != null && profile.get("avatarUrl") != null && !profile.get("avatarUrl").isBlank()) {
                row.put("avatarUrl", profile.get("avatarUrl"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("users", rows);
        return out;
    }

    @GetMapping("/user-hierarchy")
    public Map<String, Object> userHierarchy(@RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "imUserId", required = false) String imUserId,
                                             @RequestParam(value = "date", required = false) String date,
                                             @RequestParam(value = "sessionId", required = false) Long sessionId) {
        Long targetId = userId;
        if (targetId == null && imUserId != null && !imUserId.isBlank()) {
            targetId = users.findByTenantAndImUserId(TenantContext.require(), imUserId.trim())
                .map(SangongUser::getId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        }
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        SangongSession batch = null;
        if (sessionId != null) {
            batch = sessions.findBatch(sessionId);
        } else if (date == null || date.isBlank()) {
            batch = sessions.getRunning();
            if (batch == null) batch = sessions.getLatest();
        }
        SangongUser root = targetId == null ? null : users.findByTenantAndId(TenantContext.require(), targetId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        List<Map<String, Object>> members = batch == null
            ? teamReports.adminHierarchy(targetId, day)
            : teamReports.adminHierarchyBySession(targetId, batch.getId());
        Map<String, Map<String, String>> profileMap = profiles.getProfiles(members.stream()
            .map(row -> String.valueOf(row.get("imUserId"))).toList());
        for (Map<String, Object> row : members) {
            Map<String, String> profile = profileMap.get(String.valueOf(row.get("imUserId")));
            if (profile != null && profile.get("avatarUrl") != null) row.put("avatarUrl", profile.get("avatarUrl"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tenantId", TenantContext.require());
        out.put("date", batch != null ? batch.getBusinessDate() : day);
        out.put("session", batch != null ? sessions.formatSession(batch) : null);
        out.put("aggregation", batch != null ? "session" : "date");
        if (root != null) {
            out.put("root", Map.of("userId", root.getId(), "imUserId", root.getImUserId(),
                "nickname", root.getNickname(), "balance", root.getBalance()));
        } else {
            out.put("root", null);
            out.put("allTopLevel", true);
        }
        out.put("members", members);
        return out;
    }

    @GetMapping("/user-flow")
    public ResponseEntity<Map<String, Object>> userFlow(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "imUserId", required = false) String imUserId,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        Long resolvedUserId = userId;
        if (resolvedUserId == null && imUserId != null && !imUserId.isBlank()) {
            SangongUser user = users.findByImUserId(imUserId.trim()).orElse(null);
            if (user == null) {
                return PlayerBetController.err(422, "FLOW_FAILED", "用户不存在");
            }
            resolvedUserId = user.getId();
        }
        List<Map<String, Object>> rows = ledgers.listFlow(resolvedUserId, sessionId);
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("entries", rows);
        flow.put("count", rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("flow", flow);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/users/points-image")
    public ResponseEntity<Map<String, Object>> sendPointsImage(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(value = "groupId", required = false) String groupIdQuery,
            @RequestParam(value = "imGroupId", required = false) String imGroupIdQuery) {
        String groupIdRaw = Req.has(body, "groupId") ? Req.str(body, "groupId", "") : groupIdQuery;
        Long groupId = null;
        if ("0".equals(groupIdRaw) || "null".equals(groupIdRaw)) {
            groupId = 0L;
        } else if (groupIdRaw != null && !groupIdRaw.isBlank()) {
            try { groupId = Long.parseLong(groupIdRaw.trim()); } catch (NumberFormatException ignored) {}
        }
        String imGroupId = Req.has(body, "imGroupId") ? Req.str(body, "imGroupId", "") : (imGroupIdQuery == null ? "" : imGroupIdQuery);
        Map<String, Object> result = reportImage.generateAndSendPointsImage(groupId, imGroupId);
        return toImageResponse(result, 502);
    }

    @PostMapping("/trend-image")
    public ResponseEntity<Map<String, Object>> sendTrendImage() {
        SangongSession session = sessions.getRunning();
        if (session == null) {
            return PlayerBetController.err(422, "NO_SESSION", "当前未开机");
        }
        Map<String, Object> report = trendChart.buildForSession(session.getId());
        Map<String, Object> result = reportImage.generateAndSendTrend(report, im.getGameGroupId());
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return toImageResponse(result, 502);
        }
        int settledCount = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.getOrDefault("rows", List.of());
        for (Map<String, Object> row : rows) {
            if (!Boolean.TRUE.equals(row.get("placeholder"))) settledCount++;
        }
        result.put("rowCount", rows.size());
        result.put("settledCount", settledCount);
        result.put("doorCount", report.get("doorCount"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/bet-image")
    public ResponseEntity<Map<String, Object>> sendBetReportImage(
            @RequestBody(required = false) Map<String, Object> body) {
        SangongRound round = resolveReportRound(body, false);
        if (round == null) {
            return PlayerBetController.err(422, "NO_ROUND", "当前无可发送的局");
        }
        Long untilMessageId = Req.lngOrNull(body, "untilMessageId");
        Long untilMsgSeq = Req.lngOrNull(body, "untilMsgSeq");
        Object single = Req.has(body, "excludeMessageId") ? Req.body(body).get("excludeMessageId") : null;
        Object many = Req.has(body, "excludeMessageIds") ? Req.body(body).get("excludeMessageIds") : null;
        List<Long> excludeMessageIds = messageOrder.normalizeExcludeMessageIds(single, many);

        Map<String, Object> preview = null;
        Map<String, Object> report;
        String mode = "formal";
        if (round.getBetWindowOpenAt() != null && round.getDrawLockedAt() == null
            && !SangongRound.SETTLED.equals(round.getStatus())) {
            try {
                preview = rounds.previewBetWindow(round, untilMessageId, untilMsgSeq, excludeMessageIds);
                @SuppressWarnings("unchecked")
                Map<String, Object> previewReport = (Map<String, Object>) preview.get("report");
                report = previewReport;
                mode = "preview";
            } catch (RuntimeException e) {
                return PlayerBetController.err(422, "PREVIEW_FAILED", e.getMessage());
            }
        } else {
            report = bets.getRoundBetReport(round);
        }
        if (report == null) {
            return PlayerBetController.err(422, "NO_DATA", "暂无统计数据");
        }
        Map<String, Object> result = reportImage.generateAndSendBetReport(report, im.getGameGroupId());
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return toImageResponse(result, 502);
        }
        result.put("type", "bet_report");
        result.put("mode", mode);
        result.put("roundId", round.getId());
        result.put("periodNo", round.getPeriodNo());
        result.put("betCount", report.getOrDefault("betCount", 0));
        result.put("grandTotal", report.getOrDefault("grandTotal", 0));
        if (preview != null) {
            result.put("untilMessageId", preview.getOrDefault("untilMessageId", untilMessageId));
            result.put("untilMsgSeq", preview.getOrDefault("untilMsgSeq", untilMsgSeq));
            result.put("cutoffMessageId", preview.get("cutoffMessageId"));
            result.put("excludeMessageIds", preview.getOrDefault("excludeMessageIds", excludeMessageIds));
            result.put("previewCloseAt", preview.get("previewCloseAt"));
            result.put("previewCloseMsgTime", preview.get("previewCloseMsgTime"));
            result.put("pendingMessageCount", preview.getOrDefault("pendingMessageCount", 0));
            result.put("excludedAfterCutoff", preview.getOrDefault("excludedAfterCutoff", 0));
            result.put("isRecutoff", preview.getOrDefault("isRecutoff", false));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settle-image")
    public ResponseEntity<Map<String, Object>> sendSettleReportImage(
            @RequestBody(required = false) Map<String, Object> body) {
        SangongRound round = resolveReportRound(body, true);
        if (round == null) {
            return PlayerBetController.err(422, "NO_ROUND", "未找到已结算的局");
        }
        if (!SangongRound.SETTLED.equals(round.getStatus())) {
            return PlayerBetController.err(422, "NOT_SETTLED", "该局尚未结算");
        }
        Map<String, Object> settleReport;
        try {
            settleReport = settle.buildReportForRound(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "REPORT_FAILED", e.getMessage());
        }
        Map<String, Object> result = reportImage.generateAndSendSettleReport(settleReport, im.getGameGroupId());
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return toImageResponse(result, 502);
        }
        result.put("type", "settle_report");
        result.put("roundId", round.getId());
        result.put("periodNo", round.getPeriodNo());
        return ResponseEntity.ok(result);
    }

    @PostMapping({"/preview-images/send", "/send-all-preview-images"})
    public ResponseEntity<Map<String, Object>> sendAllPreviewImages() {
        List<Map<String, Object>> images = new ArrayList<>();
        int sentCount = 0;
        SangongSession session = sessions.getRunning();

        SangongRound currentRound = rounds.getCurrent();
        if (currentRound != null) {
            Map<String, Object> report = bets.getRoundBetReport(currentRound);
            images.add(sendPreview("bet_report",
                reportImage.generateAndSendBetReport(report, im.getGameGroupId()),
                currentRound.getPeriodNo()));
        } else {
            images.add(skipped("bet_report", "no_current_round"));
        }

        SangongRound settledRound = resolveLatestSettledRound(session);
        if (settledRound != null) {
            try {
                Map<String, Object> settleReport = settle.buildReportForRound(settledRound);
                images.add(sendPreview("settle_report",
                    reportImage.generateAndSendSettleReport(settleReport, im.getGameGroupId()),
                    settledRound.getPeriodNo()));
            } catch (RuntimeException e) {
                images.add(skipped("settle_report", e.getMessage()));
            }
        } else {
            images.add(skipped("settle_report", "no_settled_round"));
        }

        Map<String, Object> points = reportImage.generateAndSendPointsImage(null, im.getGameGroupId());
        if ("NO_USERS".equals(points.get("code"))) {
            images.add(skipped("user_points", "no_users"));
        } else {
            images.add(sendPreview("user_points", points, null));
        }

        if (session == null) {
            images.add(skipped("trend_chart", "no_session"));
        } else {
            Map<String, Object> trend = trendChart.buildForSession(session.getId());
            images.add(sendPreview("trend_chart",
                reportImage.generateAndSendTrend(trend, im.getGameGroupId()), null));
        }

        for (Map<String, Object> item : images) {
            if (Boolean.TRUE.equals(item.get("sent"))) sentCount++;
        }
        if (sentCount == 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", "NO_IMAGES_SENT");
            out.put("message", "暂无可发送的预览图");
            out.put("images", images);
            return ResponseEntity.status(422).body(out);
        }
        boolean failed = images.stream().anyMatch(i ->
            !Boolean.TRUE.equals(i.get("sent")) && !Boolean.TRUE.equals(i.get("skipped")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !failed);
        out.put("sentCount", sentCount);
        out.put("images", images);
        return ResponseEntity.status(failed ? 502 : 200).body(out);
    }

    @PostMapping("/settle-bill")
    public ResponseEntity<Map<String, Object>> sendSettleBill(
            @RequestBody(required = false) Map<String, Object> body) {
        SangongRound round;
        Long roundId = Req.lngOrNull(body, "roundId");
        Long sessionId = Req.lngOrNull(body, "sessionId");
        if (roundId != null) {
            round = roundRepo.findById(roundId).orElse(null);
        } else if (sessionId != null) {
            try {
                sessions.findBatch(sessionId);
            } catch (RuntimeException e) {
                return PlayerBetController.err(422, "NO_SESSION", e.getMessage());
            }
            round = roundRepo.findLastSettledBySession(sessionId).orElse(null);
        } else {
            SangongSession session = sessions.getRunning();
            if (session == null) {
                return PlayerBetController.err(422, "NO_SESSION", "当前未开机");
            }
            round = roundRepo.findLastSettledBySession(session.getId()).orElse(null);
        }
        if (round == null) {
            return PlayerBetController.err(422, "NO_ROUND", "未找到已结算的局");
        }
        if (!SangongRound.SETTLED.equals(round.getStatus())) {
            return PlayerBetController.err(422, "NOT_SETTLED", "该局尚未结算");
        }
        Map<String, Object> bill;
        try {
            bill = settleBill.build(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "BILL_FAILED", e.getMessage());
        }
        Map<String, Object> result = reportImage.generateAndSendSettleBill(bill, im.getAdminStatsGroupId());
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return toImageResponse(result, 502);
        }
        result.put("periodNo", round.getPeriodNo());
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionTotal = (Map<String, Object>) bill.getOrDefault("sessionTotal", Map.of());
        result.put("settledRoundCount", sessionTotal.getOrDefault("roundCount", 0));
        return ResponseEntity.ok(result);
    }

    private SangongRound resolveReportRound(Map<String, Object> body, boolean settledOnly) {
        Long roundId = Req.lngOrNull(body, "roundId");
        if (roundId != null) {
            return roundRepo.findById(roundId).orElse(null);
        }
        if (settledOnly) {
            return resolveLatestSettledRound(sessions.getRunning());
        }
        return rounds.getCurrent();
    }

    private SangongRound resolveLatestSettledRound(SangongSession session) {
        if (session != null) {
            SangongRound r = roundRepo.findLastSettledBySession(session.getId()).orElse(null);
            if (r != null) return r;
        }
        return roundRepo.findLastSettled().orElse(null);
    }

    private static Map<String, Object> skipped(String type, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("skipped", true);
        m.put("reason", reason);
        return m;
    }

    private static void applyProfile(Map<String, Object> target, Map<String, String> profile) {
        if (profile == null) return;
        String nickname = profile.get("nickname");
        String avatarUrl = profile.get("avatarUrl");
        if (nickname != null && !nickname.isBlank()) target.put("nickname", nickname);
        if (avatarUrl != null && !avatarUrl.isBlank()) target.put("avatarUrl", avatarUrl);
    }

    private static Map<String, Object> sendPreview(String type, Map<String, Object> sendResult, Integer periodNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (Boolean.TRUE.equals(sendResult.get("ok"))) {
            m.put("sent", true);
            if (periodNo != null) m.put("periodNo", periodNo);
            return m;
        }
        if ("IMAGE_FAILED".equals(sendResult.get("code")) || "generate_failed".equals(sendResult.get("code"))) {
            m.put("skipped", true);
            m.put("reason", "generate_failed");
            return m;
        }
        m.put("sent", false);
        m.put("reason", "im_send_failed");
        if (periodNo != null) m.put("periodNo", periodNo);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> toImageResponse(Map<String, Object> result, int imFailStatus) {
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        String code = String.valueOf(result.getOrDefault("code", "IMAGE_FAILED"));
        int status = switch (code) {
            case "NO_USERS", "NO_IM_GROUP", "NO_DATA", "NO_ROUND", "NO_SESSION" -> 422;
            case "IM_SEND_FAILED" -> imFailStatus;
            default -> 500;
        };
        return ResponseEntity.status(status).body(result);
    }
}
