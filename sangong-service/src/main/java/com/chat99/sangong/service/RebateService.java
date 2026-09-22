package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongAgentBalance;
import com.chat99.sangong.domain.SangongAgentLedger;
import com.chat99.sangong.domain.SangongRebatePending;
import com.chat99.sangong.domain.SangongTenantAgentGroup;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.AgentBalanceRepository;
import com.chat99.sangong.repository.AgentLedgerRepository;
import com.chat99.sangong.repository.RebatePendingRepository;
import com.chat99.sangong.repository.TenantAgentGroupRepository;
import com.chat99.sangong.repository.UserRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 局末返水：按 user.group_id 找到代理，按 player_rebate_pct / agent.max_rebate_pct 拆账。
 *
 * 数据流：
 *   1) 从 SettleService.report.players / bankers 抽取每个玩家的「赢流」与「输流」
 *   2) INSERT IGNORE sangong_rebate_pending(round_id, user_id, side) 落幂等记录
 *   3) 对未 processed 的 pending 行，按 agent.max_rebate 算 player_rebate 与 agent_diff
 *   4) 写 sangong_ledger(type=rebate_player) + sangong_agent_ledger(type=rebate_diff)
 *   5) 标 processed_at
 *
 * 容错：整段 try/catch 仅 log.error，不抛出（不影响结算主链路）。
 */
@Service
public class RebateService {
    private static final Logger log = LoggerFactory.getLogger(RebateService.class);

    private final RebatePendingRepository pendingRepo;
    private final UserRepository userRepo;
    private final TenantAgentGroupRepository tagRepo;
    private final AgentBalanceRepository agentBalanceRepo;
    private final AgentLedgerRepository agentLedgerRepo;
    private final BalanceService balance;
    private final TransactionTemplate tx;

    public RebateService(RebatePendingRepository pendingRepo,
                         UserRepository userRepo,
                         TenantAgentGroupRepository tagRepo,
                         AgentBalanceRepository agentBalanceRepo,
                         AgentLedgerRepository agentLedgerRepo,
                         BalanceService balance,
                         org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txManager) {
        this.pendingRepo = pendingRepo;
        this.userRepo = userRepo;
        this.tagRepo = tagRepo;
        this.agentBalanceRepo = agentBalanceRepo;
        this.agentLedgerRepo = agentLedgerRepo;
        this.balance = balance;
        org.springframework.transaction.PlatformTransactionManager ptm = txManager.getIfAvailable();
        this.tx = ptm != null ? new TransactionTemplate(ptm) : null;
    }

    /**
     * 惰性取 tenantId。
     *
     * 调用方保证 ctx 已设：
     *   - {@link SettleService#settle} 与 {@link SettleService#voidSettlement} 在请求作用域内调用
     *     （{@code TenantFilter} 已设 ctx）。
     *   - 失败时不抛出业务异常 —{@link #accrueForRound} / {@link #reverseForRound} 整段 try/catch。
     *
     * 注意：本服务当前没有任何 cron / @Scheduled 调用此方法（{@code @EnableScheduling} 已声明
     * 但无 @Scheduled 方法）。未来若有 cron 触发结算返水，调用方必须先 {@code TenantContext.run(tenantId, ...)}
     * 包一层，否则此处会 {@code TENANT_REQUIRED} 抛错（响亮失败，不静默跨租户）。
     */
    private String tenantId() {
        return TenantContext.require();
    }

    /** 入口：局末结算后落返水。从 report.players / report.bankers 抽流水。 */
    public void accrueForRound(long roundId, long sessionId, Map<String, Object> report) {
        try {
            int n = doAccrue(roundId, sessionId, report);
            if (n > 0) {
                log.info("rebate: round={} processed={} entries", roundId, n);
            }
        } catch (Exception e) {
            log.error("rebate: round={} failed: {}", roundId, e.getMessage(), e);
        }
    }

    /** 入口：冲正本局结算时反滚所有返水。 */
    public void reverseForRound(long roundId, long sessionId) {
        try {
            doReverse(roundId, sessionId);
        } catch (Exception e) {
            log.error("rebate: reverse round={} failed: {}", roundId, e.getMessage(), e);
        }
    }

    // ===== 内部实现 =====

    private int doAccrue(long roundId, long sessionId, Map<String, Object> report) {
        // (1) 把 report 里每个玩家的 win / loss 流水落到 sangong_rebate_pending
        //    win  : totalWin（玩家本局赢回的金额，即 payout 中超出本金的部分，但这里直接用 totalBet 里的输家净赢本金2倍 - 本金 = net>0 的部分）
        //    loss : totalLoss（玩家本局净输的本金）
        // 简化口径：
        //    player_win_flow  = totalWin（玩家本局「赢的流水」= 庄输给玩家的本金部分）
        //    player_loss_flow = totalLoss（玩家本局「输的流水」= 输给庄的本金）
        // 严格来说 net = totalWin - totalLoss（player 视角）。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) report.getOrDefault("players", List.of());
        for (Map<String, Object> player : players) {
            long userId = ((Number) player.get("userId")).longValue();
            long win = ((Number) player.getOrDefault("totalWin", 0)).longValue();
            long loss = ((Number) player.getOrDefault("totalLoss", 0)).longValue();
            if (win > 0) {
                pendingRepo.insertIgnore(tenantId(), roundId, userId, SangongRebatePending.SIDE_PLAYER_WIN, win);
            }
            if (loss > 0) {
                pendingRepo.insertIgnore(tenantId(), roundId, userId, SangongRebatePending.SIDE_BANKER_LOSS, loss);
            }
        }
        // banker 自身若也作为「代理名下玩家」参与返水（庄方也可能在 group 下）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bankers = (List<Map<String, Object>>) report.getOrDefault("bankers", List.of());
        for (Map<String, Object> banker : bankers) {
            long userId = ((Number) banker.get("userId")).longValue();
            // 庄方净结果：>0 表示庄赢（玩家输钱）→ banker_loss 视角是反向，记为 player_win 一侧无；记为 banker_loss
            long net = ((Number) banker.getOrDefault("net", 0)).longValue();
            if (net > 0) {
                // 庄方赢 → 庄方在「闲流」上的赢流水 = net（玩家输的钱流向了庄）
                pendingRepo.insertIgnore(tenantId(), roundId, userId, SangongRebatePending.SIDE_PLAYER_WIN, net);
            } else if (net < 0) {
                pendingRepo.insertIgnore(tenantId(), roundId, userId, SangongRebatePending.SIDE_BANKER_LOSS, -net);
            }
        }

        // (2) 处理所有未 processed 的 pending 行
        List<SangongRebatePending> unprocessed = pendingRepo.findUnprocessedByRound(roundId);
        int processed = 0;
        for (SangongRebatePending p : unprocessed) {
            if (processOne(p, roundId, sessionId)) {
                processed++;
            }
        }
        return processed;
    }

    /**
     * 单条返水计算：
     *   player_rebate = floor(player_amount * user.player_rebate_pct / 100)
     *   agent_diff    = floor(player_amount * (agent.max_rebate_pct - user.player_rebate_pct) / 100)
     *   - 玩家 group_id 缺失 或 指向 is_agent_group=0 的分组 → 跳过
     *   - 代理 max_rebate_pct=0 → 跳过（未配置）
     *   - 玩家 player_rebate_pct 超过 max_rebate_pct → 强制按 0 计算（异常兜底）
     */
    private boolean processOne(SangongRebatePending p, long roundId, long sessionId) {
        SangongUser user = userRepo.findById(p.getUserId()).orElse(null);
        if (user == null || user.getGroupId() == null) {
            markProcessed(p, 0, 0);
            return false;
        }
        // 走法 B:按 (tenant_id, group_id) 查 sangong_tenant_agent_groups
        String tenantId = TenantContext.require();
        SangongTenantAgentGroup tag = tagRepo.findByTenantAndGroup(tenantId, user.getGroupId()).orElse(null);
        if (tag == null || !tag.isActive()) {
            markProcessed(p, 0, 0);
            return false;
        }
        double maxRebatePct = tag.getMaxRebatePct();
        if (maxRebatePct <= 0d) {
            markProcessed(p, 0, 0);
            return false;
        }
        double playerPct = user.getPlayerRebatePct();
        if (playerPct > maxRebatePct) {
            // 配置异常：玩家返水高于代理最大；按 0 处理避免越界
            log.warn("rebate: user {} playerPct {} > agent maxPct {} round={} side={}",
                user.getId(), playerPct, maxRebatePct, roundId, p.getSide());
            markProcessed(p, 0, 0);
            return false;
        }
        long amount = p.getPlayerAmount();
        if (amount <= 0) {
            markProcessed(p, 0, 0);
            return false;
        }
        final long playerRebate = Math.max(0L, floorDiv(amount * (long) Math.round(playerPct * 100), 10000L));
        final long agentDiff = Math.max(0L, floorDiv(amount * (long) Math.round((maxRebatePct - playerPct) * 100), 10000L));
        // 用事务：玩家加款 + 代理余额锁定 + 写双 ledger + 标 processed
        final String agentImUserId = tag.getAgentImUserId();
        final Long groupId = user.getGroupId();
        final Long userId = user.getId();
        Boolean result = inTransaction(() -> {
            // (a) 玩家余额 + playerRebate（写 sangong_ledger type=rebate_player）
            SangongUser lockedUser = userRepo.lockById(userId);
            String playerImUserId = lockedUser != null ? lockedUser.getImUserId() : "";
            if (lockedUser != null && playerRebate > 0) {
                balance.credit(lockedUser, playerRebate, "rebate_player", sessionId,
                    "第" + roundId + "局返水(" + p.getSide() + ")", "round", roundId, agentImUserId);
            }
            // (b) 代理余额 + agentDiff
            if (agentDiff > 0 && !agentImUserId.isEmpty()) {
                SangongAgentBalance ab = agentBalanceRepo.ensureExists(tenantId(), groupId, agentImUserId);
                long newBalance = ab.getBalance() + agentDiff;
                agentBalanceRepo.updateBalance(groupId, newBalance);
                SangongAgentLedger ledger = new SangongAgentLedger();
                ledger.setTenantId(tenantId());
                ledger.setSessionId(sessionId);
                ledger.setGroupId(groupId);
                ledger.setAgentImUserId(agentImUserId);
                ledger.setType(SangongAgentLedger.TYPE_REBATE_DIFF);
                ledger.setAmount(agentDiff);
                ledger.setBalanceAfter(newBalance);
                ledger.setRefType(SangongAgentLedger.REF_ROUND);
                ledger.setRefId(roundId);
                ledger.setNote("返水差额 player=" + playerImUserId + " side=" + p.getSide());
                agentLedgerRepo.insert(ledger);
            }
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(result)) {
            markProcessed(p, playerRebate, agentDiff);
            return true;
        }
        return false;
    }

    private void markProcessed(SangongRebatePending p, long playerRebate, long agentRebate) {
        try {
            pendingRepo.markProcessed(p.getId(), playerRebate, agentRebate);
        } catch (Exception e) {
            log.warn("rebate: markProcessed id={} failed: {}", p.getId(), e.getMessage());
        }
    }

    /**
     * 冲正返水：把所有 processed 的返水反向落账。
     * 玩家侧：从 sangong_ledger 查 type=rebate_player ref=round 的 row → debit 同样金额
     * 代理侧：从 sangong_agent_ledger 查 type=rebate_diff ref=round → debit 同样金额
     */
    private void doReverse(long roundId, long sessionId) {
        // 玩家侧：直接 ledger 不重复维护；余额已 debit 即可。我们只需要把已写的 rebate_player 反向一次
        // 简化：通过 sangong_rebate_pending 找出本局所有 processed 行，按 player_rebate 反向
        List<SangongRebatePending> all = pendingRepo.findAllByRound(roundId);
        for (SangongRebatePending p : all) {
            if (p.getProcessedAt() == null) continue;
            final long playerRebate = p.getPlayerRebate();
            final long agentRebate = p.getAgentRebate();
            final long pendingUserId = p.getUserId();
            inTransaction(() -> {
                if (playerRebate > 0) {
                    SangongUser locked = userRepo.lockById(pendingUserId);
                    if (locked != null) {
                        balance.debit(locked, playerRebate, "rebate_player_void", sessionId,
                            "第" + roundId + "局返水冲正", "round", roundId, null);
                    }
                }
                if (agentRebate > 0) {
                    SangongAgentBalance ab = agentBalanceRepo.lockByGroup(tenantId(), resolveGroupId(pendingUserId));
                    if (ab != null) {
                        long newBal = ab.getBalance() - agentRebate;
                        agentBalanceRepo.updateBalance(ab.getGroupId(), newBal);
                        SangongAgentLedger ledger = new SangongAgentLedger();
                        ledger.setTenantId(tenantId());
                        ledger.setSessionId(sessionId);
                        ledger.setGroupId(ab.getGroupId());
                        ledger.setAgentImUserId(ab.getAgentImUserId());
                        ledger.setType(SangongAgentLedger.TYPE_REBATE_CORRECTION);
                        ledger.setAmount(-agentRebate);
                        ledger.setBalanceAfter(newBal);
                        ledger.setRefType(SangongAgentLedger.REF_ROUND);
                        ledger.setRefId(roundId);
                        ledger.setNote("返水冲正");
                        agentLedgerRepo.insert(ledger);
                    }
                }
                return null;
            });
        }
    }

    private long resolveGroupId(long userId) {
        Optional<SangongUser> u = userRepo.findById(userId);
        return u.map(SangongUser::getGroupId).orElse(0L);
    }

    /** 整除：模仿 PHP floor()（向负无穷取整）。 */
    private static long floorDiv(long a, long b) {
        return Math.floorDiv(a, b);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        if (tx == null) {
            return action.get();
        }
        return tx.execute(status -> action.get());
    }
}
