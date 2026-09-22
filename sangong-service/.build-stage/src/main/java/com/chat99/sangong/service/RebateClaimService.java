package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.domain.SangongAgentBalance;
import com.chat99.sangong.domain.SangongAgentLedger;
import com.chat99.sangong.domain.SangongTenantAgentGroup;
import com.chat99.sangong.repository.AgentBalanceRepository;
import com.chat99.sangong.repository.AgentLedgerRepository;
import com.chat99.sangong.repository.SessionRepository;
import com.chat99.sangong.repository.TenantAgentGroupRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 第一阶段返水领取逻辑：按租户、用户和账户类型锁定并增量领取。 */
@Service
public class RebateClaimService {
    private final NamedParameterJdbcTemplate jdbc;
    private final BalanceService balance;
    private final SessionRepository sessions;
    private final TenantAgentGroupRepository agentGroups;
    private final AgentBalanceRepository agentBalances;
    private final AgentLedgerRepository agentLedgers;

    public RebateClaimService(NamedParameterJdbcTemplate jdbc, BalanceService balance,
                              SessionRepository sessions, TenantAgentGroupRepository agentGroups,
                              AgentBalanceRepository agentBalances, AgentLedgerRepository agentLedgers) {
        this.jdbc = jdbc;
        this.balance = balance;
        this.sessions = sessions;
        this.agentGroups = agentGroups;
        this.agentBalances = agentBalances;
        this.agentLedgers = agentLedgers;
    }

    @Transactional
    public Map<String, Object> claimPlayerRebate(SangongUser user) {
        var running = sessions.findRunning();
        return claimPlayerRebate(user, running.map(s -> s.getId()).orElse(null), "MANUAL");
    }

    @Transactional
    public Map<String, Object> claimPlayerRebate(SangongUser user, Long sessionId, String claimType) {
        Map<String, Object> settled = claimAccount(user, sessionId, claimType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("player", settled.get("player"));
        out.put("agent", settled.get("agent"));
        out.put("amount", ((Number) ((Map<?, ?>) settled.get("player")).get("amount")).longValue());
        out.put("agentAmount", ((Number) ((Map<?, ?>) settled.get("agent")).get("amount")).longValue());
        return out;
    }

    private Map<String, Object> claimAccount(SangongUser user, Long sessionId, String claimType) {
        String tenantId = TenantContext.require();
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("t", tenantId).addValue("u", user.getId()).addValue("a", "PLAYER_REBATE");

        jdbc.update("INSERT IGNORE INTO sangong_rebate_accounts " +
                "(tenant_id,user_id,account_type,rebate_pct) VALUES(:t,:u,:a,:pct)",
            new MapSqlParameterSource(p.getValues()).addValue("pct", user.getPlayerRebatePct()));

        Map<String, Object> account = jdbc.queryForMap(
            "SELECT * FROM sangong_rebate_accounts WHERE tenant_id=:t AND user_id=:u " +
                "AND account_type=:a FOR UPDATE", p);
        long total = number(account.get("turnover_total"));
        long claimed = number(account.get("turnover_claimed"));
        long pendingTurnover = Math.max(0L, total - claimed);
        double pct = numberDecimal(account.get("rebate_pct"));
        long amount = Math.max(0L, (long) Math.floor(pendingTurnover * pct / 100.0d));

        if (pendingTurnover == 0) {
            return combined(result(0, 0, total, claimed, pct), agentResult(0, null, null));
        }

        long agentAmount = 0L;
        Long groupId = user.getGroupId();
        SangongTenantAgentGroup agentGroup = groupId == null ? null
            : agentGroups.lockByTenantAndGroup(tenantId, groupId);
        if (agentGroup != null && agentGroup.isActive() && agentGroup.getAgentImUserId() != null
            && !agentGroup.getAgentImUserId().isBlank()) {
            double diffPct = Math.max(0d, agentGroup.getMaxRebatePct() - pct);
            agentAmount = Math.max(0L, (long) Math.floor(pendingTurnover * diffPct / 100.0d));
        }

        jdbc.update("UPDATE sangong_rebate_accounts SET turnover_claimed=:tc, " +
                "amount_claimed=amount_claimed+:amount, last_claimed_at=NOW() " +
                "WHERE tenant_id=:t AND user_id=:u AND account_type=:a",
            new MapSqlParameterSource(p.getValues())
                .addValue("tc", total).addValue("amount", amount));

        if (amount > 0) {
            balance.credit(user, amount, "rebate_player", sessionId,
                "申请返水", "rebate", null, user.getImUserId());
        }

        if (agentAmount > 0 && agentGroup != null) {
            SangongAgentBalance agentBalance = agentBalances.ensureExists(
                tenantId, agentGroup.getGroupId(), agentGroup.getAgentImUserId());
            long newBalance = agentBalance.getBalance() + agentAmount;
            agentBalances.updateBalance(agentGroup.getGroupId(), newBalance);
            SangongAgentLedger ledger = new SangongAgentLedger();
            ledger.setTenantId(tenantId); ledger.setSessionId(sessionId);
            ledger.setGroupId(agentGroup.getGroupId()); ledger.setAgentImUserId(agentGroup.getAgentImUserId());
            ledger.setType(SangongAgentLedger.TYPE_REBATE_DIFF); ledger.setAmount(agentAmount);
            ledger.setBalanceAfter(newBalance); ledger.setRefType(SangongAgentLedger.REF_REBATE);
            ledger.setRefId(user.getId()); ledger.setNote("玩家 " + user.getImUserId() + " 申请返水产生代理差额");
            agentLedgers.insert(ledger);
        }

        String normalizedClaimType = "CLOSE".equalsIgnoreCase(claimType) ? "AUTO" : "MANUAL";
        String ref = normalizedClaimType.toLowerCase() + ":player:" + tenantId + ":" + user.getId() + ":" + total;
        jdbc.update("INSERT INTO sangong_rebate_ledger " +
                "(tenant_id,session_id,user_id,account_type,turnover_amount,rebate_pct,amount,claim_type,reference_id) " +
                "VALUES(:t,:s,:u,:a,:turnover,:pct,:amount,:claimType,:ref) " +
                "ON DUPLICATE KEY UPDATE id=id",
            new MapSqlParameterSource(p.getValues()).addValue("turnover", pendingTurnover)
                .addValue("s", sessionId).addValue("pct", pct).addValue("amount", amount)
                .addValue("claimType", normalizedClaimType).addValue("ref", ref));
        return combined(result(amount, 0, total, total, pct),
            agentResult(agentAmount, agentGroup == null ? null : agentGroup.getGroupId(),
                agentGroup == null ? null : agentGroup.getAgentImUserId()));
    }

    private static Map<String, Object> combined(Map<String, Object> player, Map<String, Object> agent) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("player", player); out.put("agent", agent); return out;
    }

    private static Map<String, Object> agentResult(long amount, Long groupId, String agentImUserId) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("amount", amount);
        out.put("groupId", groupId); out.put("agentImUserId", agentImUserId); return out;
    }

    /** 刷新当前用户直属下级产生的代理差额；按租户和用户树隔离。 */
    @Transactional
    public void refreshAgentDiff(long agentUserId) {
        String t = TenantContext.require();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", t).addValue("u", agentUserId);
        jdbc.update("INSERT IGNORE INTO sangong_rebate_accounts(tenant_id,user_id,account_type,rebate_pct) " +
            "SELECT :t,:u,'AGENT_DIFF',COALESCE(player_rebate_pct,0) FROM sangong_users WHERE id=:u", p);
        Double agentPct = jdbc.queryForObject("SELECT rebate_pct FROM sangong_rebate_accounts " +
            "WHERE tenant_id=:t AND user_id=:u AND account_type='AGENT_DIFF' FOR UPDATE", p, Double.class);
        if (agentPct == null || agentPct <= 0) return;
        Long diff = jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(0, " +
            ":agentPct - COALESCE(a.rebate_pct,0)) * a.turnover_total / 100),0) " +
            "FROM sangong_user_hierarchy h " +
            "JOIN sangong_rebate_accounts a ON a.tenant_id=h.tenant_id AND a.user_id=h.user_id " +
            "AND a.account_type='PLAYER_REBATE' " +
            "WHERE h.tenant_id=:t AND h.parent_user_id=:u", p.addValue("agentPct", agentPct), Long.class);
        jdbc.update("UPDATE sangong_rebate_accounts SET turnover_total=:turnover, amount_total=:amount " +
            "WHERE tenant_id=:t AND user_id=:u AND account_type='AGENT_DIFF'",
            new MapSqlParameterSource().addValue("t", t).addValue("u", agentUserId)
                .addValue("turnover", diff == null ? 0 : diff).addValue("amount", diff == null ? 0 : diff));
    }

    public Map<String, Object> status(SangongUser user) {
        String tenantId = TenantContext.require();
        var rows = jdbc.queryForList("SELECT account_type, turnover_total, turnover_claimed, " +
                "amount_total, amount_claimed, rebate_pct FROM sangong_rebate_accounts " +
                "WHERE tenant_id=:t AND user_id=:u", new MapSqlParameterSource()
                .addValue("t", tenantId).addValue("u", user.getId()));
        for (Map<String, Object> row : rows) {
            Object rawPct = row.get("rebate_pct");
            double pct = rawPct == null ? 0d : ((Number) rawPct).doubleValue();
            row.put("rebatePer10000", Math.round(pct * 100.0d));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", tenantId); out.put("userId", user.getId());
        out.put("accounts", rows);
        return out;
    }

    private static Map<String, Object> result(long amount, long pending, long total, long claimed, double pct) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("amount", amount); out.put("rebatePct", pct);
        out.put("rebatePer10000", Math.round(pct * 100.0d));
        out.put("turnoverTotal", total); out.put("turnoverClaimed", claimed);
        out.put("pendingTurnover", pending); return out;
    }
    private static long number(Object v) { return v == null ? 0L : ((Number) v).longValue(); }
    private static double numberDecimal(Object v) { return v == null ? 0d : ((Number) v).doubleValue(); }
}
