package com.chat99.server.robot.agent;

import com.chat99.server.robot.RobotSyncSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class AgentRebateService {

    private final AgentPlayerRepository repository;

    public AgentRebateService(AgentPlayerRepository repository) {
        this.repository = repository;
    }

    public AgentPlayerProfileResponse getPlayerProfile(String machineCode, String userId) {
        AgentPlayerContext context = requirePlayerContext(machineCode, userId);
        AgentPlayerRow player = context.player();
        boolean isAgent = AgentRebateMath.isAgentByRebateRate(player.rebateRate());
        return new AgentPlayerProfileResponse(
            userId,
            player.playerNo(),
            displayName(player),
            player.playerType(),
            player.levelNo(),
            player.balance(),
            player.rebateRate(),
            isAgent);
    }

    public AgentCurrentRebateResponse getCurrentRebate(String machineCode, String userId) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        AgentPlayerRow agent = context.player();
        String playerGroupId = context.playerGroupId();
        List<AgentPlayerRow> visible = repository.findVisibleSnapshots(playerGroupId, userId);
        AgentRebateSummary summary = buildCurrentSummary(agent, visible, playerGroupId);
        PersonalRebateSummary personal = buildPersonalSummary(agent);
        return new AgentCurrentRebateResponse(
            userId,
            RobotSyncSupport.currentBusinessDate(),
            summary,
            personal);
    }

    public AgentHistoryRebateResponse getHistoryRebate(String machineCode, String userId, LocalDate startDate, LocalDate endDate) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        String playerGroupId = context.playerGroupId();
        validateDateRange(startDate, endDate);

        List<AgentDailySummaryRow> rows = repository.findVisibleDailySummaries(
            playerGroupId, userId, startDate, endDate);
        Map<LocalDate, List<AgentDailySummaryRow>> grouped = new HashMap<>();
        for (AgentDailySummaryRow row : rows) {
            grouped.computeIfAbsent(row.businessDate(), ignored -> new ArrayList<>()).add(row);
        }

        List<AgentHistoryDaySummary> days = grouped.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> buildHistoryDay(entry.getKey(), entry.getValue(), playerGroupId, userId))
            .toList();

        AgentRebateSummary total = aggregateHistoryDays(days);
        return new AgentHistoryRebateResponse(userId, startDate, endDate, days, total);
    }

    public AgentDescendantListResponse getDescendants(String machineCode, String userId, String scope) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        String normalizedScope = normalizeScope(scope);
        List<AgentPlayerRow> rows = listDescendantRows(context, userId, normalizedScope);
        List<AgentDescendantItem> items = rows.stream()
            .map(row -> toDescendantItem(context.playerGroupId(), row))
            .toList();
        return new AgentDescendantListResponse(userId, normalizedScope, items, items.size());
    }

    public FirstLevelAgentGroupsResponse getFirstLevelAgentGroups(String machineCode, String userId) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        String playerGroupId = context.playerGroupId();
        List<FirstLevelAgentGroup> agents = repository.findDirectChildren(playerGroupId, userId).stream()
            .filter(row -> AgentRebateMath.isAgentByRebateRate(row.rebateRate()))
            .map(agent -> {
                List<AgentPlayerRow> rows = repository.findVisibleSnapshots(playerGroupId, agent.wxid()).stream()
                    .filter(row -> !row.wxid().equals(agent.wxid()))
                    .toList();
                Map<String, List<AgentPlayerRow>> rowsByParent = new HashMap<>();
                for (AgentPlayerRow row : rows) {
                    rowsByParent.computeIfAbsent(row.directParentWxid(), ignored -> new ArrayList<>()).add(row);
                }
                List<AgentDescendantTreeNode> children = buildDescendantTree(
                    agent.wxid(), rowsByParent, playerGroupId, new HashSet<>());
                List<AgentDescendantItem> descendants = rows.stream()
                    .map(row -> toDescendantItem(playerGroupId, row))
                    .toList();
                return new FirstLevelAgentGroup(
                    toDescendantItem(playerGroupId, agent),
                    children,
                    descendants,
                    descendants.size());
            })
            .toList();
        int descendantTotal = agents.stream()
            .mapToInt(FirstLevelAgentGroup::descendantCount)
            .sum();
        return new FirstLevelAgentGroupsResponse(
            userId, agents, agents.size(), descendantTotal);
    }

    private List<AgentDescendantTreeNode> buildDescendantTree(
            String parentWxid,
            Map<String, List<AgentPlayerRow>> rowsByParent,
            String playerGroupId,
            Set<String> ancestors) {
        if (!ancestors.add(parentWxid)) {
            return List.of();
        }
        List<AgentDescendantTreeNode> result = rowsByParent.getOrDefault(parentWxid, List.of()).stream()
            .map(row -> {
                List<AgentDescendantTreeNode> children = buildDescendantTree(
                    row.wxid(), rowsByParent, playerGroupId, new HashSet<>(ancestors));
                int descendantCount = children.stream()
                    .mapToInt(child -> 1 + child.descendantCount())
                    .sum();
                return new AgentDescendantTreeNode(
                    toDescendantItem(playerGroupId, row),
                    children,
                    children.size(),
                    descendantCount);
            })
            .toList();
        ancestors.remove(parentWxid);
        return result;
    }

    public AgentDescendantDetailResponse getDescendantDetail(String machineCode, String userId, String targetUserId) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        requireDescendant(context, userId, targetUserId);
        String playerGroupId = context.playerGroupId();
        AgentPlayerRow row = repository.findPlayer(playerGroupId, targetUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND"));
        // 目标本人 + 其整棵下级树（排除手动托 playerType=1），口径与 rebate/current 的 visible 一致
        List<AgentPlayerRow> team = repository.findVisibleSnapshots(playerGroupId, targetUserId);
        BigDecimal teamTotalUp = AgentRebateMath.sumMoney(team.stream().map(AgentPlayerRow::totalUp).toList());
        BigDecimal teamTotalDown = AgentRebateMath.sumMoney(team.stream().map(AgentPlayerRow::totalDown).toList());
        return new AgentDescendantDetailResponse(
            userId,
            toDescendantItem(playerGroupId, row),
            repository.findDirectChildren(playerGroupId, targetUserId).size(),
            repository.countDescendants(playerGroupId, targetUserId),
            teamTotalUp,
            teamTotalDown);
    }

    public AgentDescendantHistoryResponse getDescendantHistory(
            String machineCode, String userId, LocalDate startDate, LocalDate endDate, String targetUserId) {
        AgentPlayerContext context = requireAgentContext(machineCode, userId);
        validateDateRange(startDate, endDate);
        if (targetUserId != null && !targetUserId.isBlank()) {
            requireDescendant(context, userId, targetUserId.trim());
        }

        List<AgentDailySummaryRow> rows = repository.findVisibleDailySummaries(
            context.playerGroupId(), userId, startDate, endDate);
        List<AgentDescendantHistoryItem> items = rows.stream()
            .filter(row -> !row.wxid().equals(userId))
            .filter(row -> targetUserId == null || targetUserId.isBlank() || row.wxid().equals(targetUserId.trim()))
            .map(this::toDescendantHistoryItem)
            .toList();
        String normalizedTarget = targetUserId == null || targetUserId.isBlank() ? null : targetUserId.trim();
        return new AgentDescendantHistoryResponse(userId, startDate, endDate, normalizedTarget, items, items.size());
    }

    private List<AgentPlayerRow> listDescendantRows(AgentPlayerContext context, String userId, String scope) {
        if ("direct".equals(scope)) {
            return repository.findDirectChildren(context.playerGroupId(), userId);
        }
        return repository.findVisibleSnapshots(context.playerGroupId(), userId).stream()
            .filter(row -> !row.wxid().equals(userId))
            .toList();
    }

    private void requireDescendant(AgentPlayerContext context, String userId, String targetUserId) {
        if (!repository.isDescendant(context.playerGroupId(), userId, targetUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_IN_SCOPE");
        }
    }

    private AgentDescendantItem toDescendantItem(String playerGroupId, AgentPlayerRow row) {
        return new AgentDescendantItem(
            row.wxid(),
            row.playerNo(),
            displayName(row),
            row.playerType(),
            row.levelNo(),
            AgentRebateMath.isAgentByRebateRate(row.rebateRate()),
            row.directParentWxid(),
            row.directParentNo(),
            row.balance(),
            row.totalFlow(),
            row.usedFlow(),
            row.remainingFlow(),
            row.totalUp(),
            row.totalDown(),
            row.totalProfitLoss(),
            row.totalProfitLoss() == null
                ? AgentRebateMath.zero()
                : AgentRebateMath.money(row.totalProfitLoss().negate()),
            row.rebateRate(),
            row.totalRebate(),
            AgentRebateMath.pendingRebate(row.remainingFlow(), row.rebateRate()));
    }

    private AgentDescendantHistoryItem toDescendantHistoryItem(AgentDailySummaryRow row) {
        return new AgentDescendantHistoryItem(
            row.businessDate(),
            row.wxid(),
            row.playerNo(),
            row.displayName(),
            row.playerType(),
            row.directParentWxid(),
            row.balance(),
            row.totalFlow(),
            row.totalUp(),
            row.totalDown(),
            row.totalProfitLoss(),
            row.totalProfitLoss() == null
                ? AgentRebateMath.zero()
                : AgentRebateMath.money(row.totalProfitLoss().negate()),
            row.totalRebate(),
            row.pendingRebate(),
            row.rebateRate());
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope.trim())) {
            return "all";
        }
        if ("direct".equalsIgnoreCase(scope.trim())) {
            return "direct";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE");
    }

    private AgentPlayerContext requirePlayerContext(String machineCode, String userId) {
        return repository.findPlayer(machineCode, userId)
            .map(player -> new AgentPlayerContext(machineCode, player))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND"));
    }

    private AgentPlayerContext requireAgentContext(String machineCode, String userId) {
        AgentPlayerContext context = requirePlayerContext(machineCode, userId);
        if (!AgentRebateMath.isAgentByRebateRate(context.player().rebateRate())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_AGENT");
        }
        return context;
    }

    private PersonalRebateSummary buildPersonalSummary(AgentPlayerRow agent) {
        BigDecimal rebateRate = agent.rebateRate() == null ? BigDecimal.ZERO : agent.rebateRate();
        BigDecimal pendingRebate = AgentRebateMath.pendingRebateFloor(agent.remainingFlow(), rebateRate);
        String rebateRateUnit = agent.rebateRateUnit() == null || agent.rebateRateUnit().isBlank()
            ? "per_10000"
            : agent.rebateRateUnit();

        // 级差待结算以机器人同步的 agentPending* 为准。
        // 不可对直属下级 remainingFlow 重算：下级流水仍属其本人待反水，代理结清后 remaining 不会清零，
        // 重算会导致已领取级差（如 totalRebate 已含 98）仍显示 agentPendingRebate=98。
        BigDecimal agentPendingFlow = AgentRebateMath.money(agent.agentPendingFlow());
        BigDecimal agentPendingRebate = AgentRebateMath.money(agent.agentPendingRebate());
        BigDecimal totalPending = AgentRebateMath.money(pendingRebate.add(agentPendingRebate));

        return new PersonalRebateSummary(
            AgentRebateMath.money(agent.balance()),
            AgentRebateMath.money(agent.totalFlow()),
            AgentRebateMath.money(agent.totalProfitLoss()),
            AgentRebateMath.money(agent.totalRebate()),
            AgentRebateMath.money(agent.remainingFlow()),
            pendingRebate,
            agentPendingFlow,
            agentPendingRebate,
            totalPending,
            totalPending,
            rebateRate,
            rebateRateUnit);
    }

    private AgentRebateSummary buildCurrentSummary(
            AgentPlayerRow agent, List<AgentPlayerRow> visible, String playerGroupId) {
        int agentCount = 0;
        int playerCount = 0;
        for (AgentPlayerRow row : visible) {
            if (row.wxid().equals(agent.wxid())) {
                continue;
            }
            if (repository.hasDescendants(playerGroupId, row.wxid())) {
                agentCount++;
            } else {
                playerCount++;
            }
        }

        BigDecimal totalBalance = AgentRebateMath.sumMoney(visible.stream().map(AgentPlayerRow::balance).toList());
        BigDecimal totalFlow = AgentRebateMath.sumMoney(visible.stream().map(AgentPlayerRow::totalFlow).toList());
        BigDecimal totalUp = AgentRebateMath.sumMoney(visible.stream().map(AgentPlayerRow::totalUp).toList());
        BigDecimal totalDown = AgentRebateMath.sumMoney(visible.stream().map(AgentPlayerRow::totalDown).toList());
        BigDecimal playerProfitLoss = AgentRebateMath.sumMoney(visible.stream().map(AgentPlayerRow::totalProfitLoss).toList());
        BigDecimal totalRebated = AgentRebateMath.sumMoney(visible.stream().map(AgentPlayerRow::totalRebate).toList());
        BigDecimal pendingRebate = AgentRebateMath.sumMoney(visible.stream()
            .map(row -> AgentRebateMath.pendingRebate(row.remainingFlow(), row.rebateRate()))
            .toList());

        long dataTime = visible.stream()
            .mapToLong(AgentPlayerRow::sourceUpdatedAt)
            .max()
            .orElse(agent.sourceUpdatedAt());

        return new AgentRebateSummary(
            agent.wxid(),
            agent.playerNo(),
            displayName(agent),
            agentCount,
            playerCount,
            totalBalance,
            totalFlow,
            playerProfitLoss,
            AgentRebateMath.money(playerProfitLoss.negate()),
            totalUp,
            totalDown,
            totalRebated,
            pendingRebate,
            Instant.ofEpochSecond(dataTime).atOffset(ZoneOffset.ofHours(8)).toString());
    }

    private AgentHistoryDaySummary buildHistoryDay(
            LocalDate businessDate, List<AgentDailySummaryRow> rows, String playerGroupId, String agentWxid) {
        int agentCount = 0;
        int playerCount = 0;
        for (AgentDailySummaryRow row : rows) {
            if (row.wxid().equals(agentWxid)) {
                continue;
            }
            if (repository.hasDescendants(playerGroupId, row.wxid())) {
                agentCount++;
            } else {
                playerCount++;
            }
        }

        BigDecimal totalBalance = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::balance).toList());
        BigDecimal totalFlow = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::totalFlow).toList());
        BigDecimal totalUp = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::totalUp).toList());
        BigDecimal totalDown = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::totalDown).toList());
        BigDecimal playerProfitLoss = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::totalProfitLoss).toList());
        BigDecimal totalRebated = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::totalRebate).toList());
        BigDecimal pendingRebate = AgentRebateMath.sumMoney(rows.stream().map(AgentDailySummaryRow::pendingRebate).toList());

        return new AgentHistoryDaySummary(
            businessDate,
            agentCount,
            playerCount,
            totalBalance,
            totalFlow,
            playerProfitLoss,
            AgentRebateMath.money(playerProfitLoss.negate()),
            totalUp,
            totalDown,
            totalRebated,
            pendingRebate);
    }

    private AgentRebateSummary aggregateHistoryDays(List<AgentHistoryDaySummary> days) {
        if (days.isEmpty()) {
            return new AgentRebateSummary(
                null, null, null, 0, 0,
                AgentRebateMath.zero(), AgentRebateMath.zero(), AgentRebateMath.zero(), AgentRebateMath.zero(),
                AgentRebateMath.zero(), AgentRebateMath.zero(), AgentRebateMath.zero(), AgentRebateMath.zero(),
                null);
        }
        return new AgentRebateSummary(
            null,
            null,
            null,
            days.stream().mapToInt(AgentHistoryDaySummary::agentCount).max().orElse(0),
            days.stream().mapToInt(AgentHistoryDaySummary::playerCount).max().orElse(0),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::totalBalance).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::totalFlow).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::playerProfitLoss).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::platformProfitLoss).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::totalUp).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::totalDown).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::totalRebated).toList()),
            AgentRebateMath.sumMoney(days.stream().map(AgentHistoryDaySummary::pendingRebate).toList()),
            null);
    }

    private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DATE_RANGE_REQUIRED");
        }
        if (endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }
        if (startDate.plusDays(93).isBefore(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LARGE");
        }
    }

    private static String displayName(AgentPlayerRow player) {
        if (player.displayName() != null && !player.displayName().isBlank()) {
            return player.displayName();
        }
        return player.nickname() != null ? player.nickname() : player.wxid();
    }

    public record AgentPlayerProfileResponse(
        String userId,
        String playerNo,
        String displayName,
        String playerType,
        int levelNo,
        BigDecimal balance,
        BigDecimal rebateRate,
        boolean isAgent) {}

    public record AgentRebateSummary(
        String agentWxid,
        String agentNo,
        String agentName,
        int agentCount,
        int playerCount,
        BigDecimal totalBalance,
        BigDecimal totalFlow,
        BigDecimal playerProfitLoss,
        BigDecimal platformProfitLoss,
        BigDecimal totalUp,
        BigDecimal totalDown,
        BigDecimal totalRebated,
        BigDecimal pendingRebate,
        String dataTime) {}

    public record PersonalRebateSummary(
        BigDecimal balance,
        BigDecimal totalFlow,
        BigDecimal totalProfitLoss,
        BigDecimal totalRebate,
        BigDecimal remainingFlow,
        BigDecimal pendingRebate,
        BigDecimal agentPendingFlow,
        BigDecimal agentPendingRebate,
        BigDecimal totalPendingRebate,
        /** totalRebate1 = pendingRebate + agentPendingRebate（与 totalPendingRebate 同值）。 */
        BigDecimal totalRebate1,
        BigDecimal rebateRate,
        String rebateRateUnit) {}

    public record AgentCurrentRebateResponse(
        String userId,
        /** 当前业务日标签（中国时间；07:00 前仍属前一日）。 */
        LocalDate businessDate,
        AgentRebateSummary summary,
        PersonalRebateSummary personal) {}

    public record AgentHistoryDaySummary(
        LocalDate businessDate,
        int agentCount,
        int playerCount,
        BigDecimal totalBalance,
        BigDecimal totalFlow,
        BigDecimal playerProfitLoss,
        BigDecimal platformProfitLoss,
        BigDecimal totalUp,
        BigDecimal totalDown,
        BigDecimal totalRebated,
        BigDecimal pendingRebate) {}

    public record AgentHistoryRebateResponse(
        String userId,
        LocalDate startDate,
        LocalDate endDate,
        List<AgentHistoryDaySummary> days,
        AgentRebateSummary total) {}

    public record AgentDescendantItem(
        String userId,
        String playerNo,
        String displayName,
        String playerType,
        int levelNo,
        boolean isAgent,
        String directParentUserId,
        String directParentNo,
        BigDecimal balance,
        BigDecimal totalFlow,
        BigDecimal usedFlow,
        BigDecimal remainingFlow,
        BigDecimal totalUp,
        BigDecimal totalDown,
        BigDecimal playerProfitLoss,
        BigDecimal platformProfitLoss,
        BigDecimal rebateRate,
        BigDecimal totalRebated,
        BigDecimal pendingRebate) {}

    public record AgentDescendantListResponse(
        String userId,
        String scope,
        List<AgentDescendantItem> items,
        int total) {}

    public record FirstLevelAgentGroup(
        AgentDescendantItem agent,
        List<AgentDescendantTreeNode> children,
        List<AgentDescendantItem> descendants,
        int descendantCount) {}

    public record AgentDescendantTreeNode(
        AgentDescendantItem item,
        List<AgentDescendantTreeNode> children,
        int childCount,
        int descendantCount) {}

    public record FirstLevelAgentGroupsResponse(
        String userId,
        List<FirstLevelAgentGroup> agents,
        int agentCount,
        int descendantTotal) {}

    public record AgentDescendantDetailResponse(
        String userId,
        AgentDescendantItem item,
        int directChildCount,
        int descendantCount,
        BigDecimal teamTotalUp,
        BigDecimal teamTotalDown) {}

    public record AgentDescendantHistoryItem(
        LocalDate businessDate,
        String userId,
        String playerNo,
        String displayName,
        String playerType,
        String directParentUserId,
        BigDecimal balance,
        BigDecimal totalFlow,
        BigDecimal totalUp,
        BigDecimal totalDown,
        BigDecimal playerProfitLoss,
        BigDecimal platformProfitLoss,
        BigDecimal totalRebated,
        BigDecimal pendingRebate,
        BigDecimal rebateRate) {}

    public record AgentDescendantHistoryResponse(
        String userId,
        LocalDate startDate,
        LocalDate endDate,
        String targetUserId,
        List<AgentDescendantHistoryItem> items,
        int total) {}
}
