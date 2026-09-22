package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongAgentBalance;
import com.chat99.sangong.domain.SangongAgentLedger;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.AgentBalanceRepository;
import com.chat99.sangong.repository.AgentLedgerRepository;
import com.chat99.sangong.repository.TenantAgentGroupRepository;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 代理与名下玩家的双向划转。
 *
 * 设计：
 *   代理侧有独立余额（sangong_agent_balance）。
 *   - agentToPlayer：agent_balance -= amount, player.balance += amount
 *   - playerToAgent：player.balance -= amount, agent_balance += amount
 *   - 必须同组（玩家必须在代理的 group_id 下）
 *   - 写 sangong_agent_ledger（agent_transfer_in / agent_transfer_out）
 *   - 写 sangong_ledger（type=admin_credit / admin_debit，operator=agent_im_user_id）
 *
 * 容错：整个调用包在事务内，任一失败回滚全部。
 */
@Service
public class AgentTransferService {
    private static final Logger log = LoggerFactory.getLogger(AgentTransferService.class);

    private final AgentBalanceRepository agentBalanceRepo;
    private final AgentLedgerRepository agentLedgerRepo;
    private final UserRepository userRepo;
    private final TenantAgentGroupRepository tagRepo;
    private final BalanceService balance;
    private final SessionService sessions;
    private final TransactionTemplate tx;

    public AgentTransferService(AgentBalanceRepository agentBalanceRepo,
                                AgentLedgerRepository agentLedgerRepo,
                                UserRepository userRepo,
                                TenantAgentGroupRepository tagRepo,
                                BalanceService balance,
                                SessionService sessions,
                                org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txManager) {
        this.agentBalanceRepo = agentBalanceRepo;
        this.agentLedgerRepo = agentLedgerRepo;
        this.userRepo = userRepo;
        this.tagRepo = tagRepo;
        this.balance = balance;
        this.sessions = sessions;
        org.springframework.transaction.PlatformTransactionManager ptm = txManager.getIfAvailable();
        this.tx = ptm != null ? new TransactionTemplate(ptm) : null;
    }

    private String tenantId() {
        return TenantContext.require();
    }

    /**
     * 代理 → 玩家：扣代理余额、加玩家余额。
     * @param agentImUserId  代理 IM 用户 ID
     * @param groupId        划转所属的分组 ID
     * @param toUserId       玩家 ID（必须 group_id == groupId）
     * @param amount         划转金额（>0）
     * @param operator       操作人（一般是 agent 自己或群主）
     * @param note           备注
     */
    public Map<String, Object> agentToPlayer(String agentImUserId, long groupId, long toUserId,
                                             long amount, String operator, String note) {
        validate(amount);
        if (agentImUserId == null || agentImUserId.isBlank()) {
            throw new IllegalArgumentException("agentImUserId 必填");
        }
        // 走法 B:查 sangong_tenant_agent_groups(按租户 + 分组)
        var tagOpt1 = tagRepo.findByTenantAndGroup(tenantId(), groupId);
        if (tagOpt1.isEmpty()) {
            throw new IllegalArgumentException("分组不存在或本租户未配置: " + groupId);
        }
        var tag1 = tagOpt1.get();
        if (!tag1.isActive() || !agentImUserId.equals(tag1.getAgentImUserId())) {
            throw new IllegalStateException("NOT_AGENT_OF_GROUP");
        }

        Map<String, Object> result = newTransaction(() -> {
            Long sessionId = currentSessionId();
            SangongUser player = userRepo.lockById(toUserId);
            if (player == null) {
                throw new IllegalArgumentException("玩家不存在: " + toUserId);
            }
            if (player.getGroupId() == null || player.getGroupId() != groupId) {
                throw new IllegalStateException("CROSS_GROUP_FORBIDDEN");
            }
            SangongAgentBalance ab = agentBalanceRepo.lockByGroup(tenantId(), groupId);
            if (ab == null) {
                throw new IllegalStateException("代理余额不存在");
            }
            if (ab.getBalance() < amount) {
                throw new InsufficientAgentBalanceException(ab.getBalance());
            }
            long newAgentBal = ab.getBalance() - amount;

            // (a) 代理 ledger: agent_transfer_out
            SangongAgentLedger ledger = new SangongAgentLedger();
            ledger.setTenantId(tenantId());
            ledger.setSessionId(sessionId);
            ledger.setGroupId(groupId);
            ledger.setAgentImUserId(agentImUserId);
            ledger.setType(SangongAgentLedger.TYPE_TRANSFER_OUT);
            ledger.setAmount(-amount);
            ledger.setBalanceAfter(newAgentBal);
            ledger.setRefType(SangongAgentLedger.REF_TRANSFER);
            ledger.setRefId(player.getId());
            ledger.setNote(note == null ? "" : note);
            agentLedgerRepo.insert(ledger);

            agentBalanceRepo.updateBalance(groupId, newAgentBal);

            // (b) 玩家余额 +
            balance.credit(player, amount, "admin_credit", sessionId,
                "代理转入 " + (note == null ? "" : note), "transfer", ledger.getId(),
                operator == null ? agentImUserId : operator);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("direction", "agent_to_player");
            r.put("agentBalanceAfter", newAgentBal);
            r.put("playerBalanceAfter", player.getBalance());
            r.put("transferId", ledger.getId());
            return r;
        });
        log.info("agent-transfer: agent={} group={} toUser={} amount={} dir=agent_to_player",
            agentImUserId, groupId, toUserId, amount);
        return result;
    }

    /**
     * 玩家 → 代理：扣玩家余额、加代理余额。
     */
    public Map<String, Object> playerToAgent(String agentImUserId, long groupId, long fromUserId,
                                             long amount, String operator, String note) {
        validate(amount);
        if (agentImUserId == null || agentImUserId.isBlank()) {
            throw new IllegalArgumentException("agentImUserId 必填");
        }
        // 走法 B:查 sangong_tenant_agent_groups
        var tagOpt2 = tagRepo.findByTenantAndGroup(tenantId(), groupId);
        if (tagOpt2.isEmpty()) {
            throw new IllegalArgumentException("分组不存在或本租户未配置: " + groupId);
        }
        var tag2 = tagOpt2.get();
        if (!tag2.isActive() || !agentImUserId.equals(tag2.getAgentImUserId())) {
            throw new IllegalStateException("NOT_AGENT_OF_GROUP");
        }

        Map<String, Object> result = inTransaction(() -> {
            Long sessionId = currentSessionId();
            SangongUser player = userRepo.lockById(fromUserId);
            if (player == null) {
                throw new IllegalArgumentException("玩家不存在: " + fromUserId);
            }
            if (player.getGroupId() == null || player.getGroupId() != groupId) {
                throw new IllegalStateException("CROSS_GROUP_FORBIDDEN");
            }
            if (player.getBalance() < amount) {
                throw new com.chat99.sangong.common.InsufficientBalanceException(player.getBalance());
            }

            SangongAgentBalance ab = agentBalanceRepo.ensureExists(tenantId(), groupId, agentImUserId);
            long newAgentBal = ab.getBalance() + amount;

            // (a) 玩家余额 -
            balance.debit(player, amount, "admin_debit", sessionId,
                "代理转出 " + (note == null ? "" : note), "transfer", null,
                operator == null ? agentImUserId : operator);

            // (b) 代理 ledger: agent_transfer_in
            SangongAgentLedger ledger = new SangongAgentLedger();
            ledger.setTenantId(tenantId());
            ledger.setSessionId(sessionId);
            ledger.setGroupId(groupId);
            ledger.setAgentImUserId(agentImUserId);
            ledger.setType(SangongAgentLedger.TYPE_TRANSFER_IN);
            ledger.setAmount(amount);
            ledger.setBalanceAfter(newAgentBal);
            ledger.setRefType(SangongAgentLedger.REF_TRANSFER);
            ledger.setRefId(player.getId());
            ledger.setNote(note == null ? "" : note);
            agentLedgerRepo.insert(ledger);

            agentBalanceRepo.updateBalance(groupId, newAgentBal);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("direction", "player_to_agent");
            r.put("agentBalanceAfter", newAgentBal);
            r.put("playerBalanceAfter", player.getBalance());
            r.put("transferId", ledger.getId());
            return r;
        });
        log.info("agent-transfer: agent={} group={} fromUser={} amount={} dir=player_to_agent",
            agentImUserId, groupId, fromUserId, amount);
        return result;
    }

    private static void validate(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("INVALID_AMOUNT: amount must be > 0");
        }
    }

    private Long currentSessionId() {
        var session = sessions.getRunning();
        return session == null ? null : session.getId();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        if (tx == null) {
            return action.get();
        }
        return tx.execute(status -> action.get());
    }

    private <T> T newTransaction(java.util.function.Supplier<T> action) {
        return inTransaction(action);
    }

    /** 代理余额不足异常。 */
    public static class InsufficientAgentBalanceException extends com.chat99.sangong.common.BusinessException {
        private final long currentBalance;
        public InsufficientAgentBalanceException(long currentBalance) {
            super("INSUFFICIENT_AGENT_BALANCE", "代理余额不足", 422);
            this.currentBalance = currentBalance;
        }
        public long getCurrentBalance() { return currentBalance; }
    }
}
