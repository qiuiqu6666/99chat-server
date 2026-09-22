-- 从“每局即时返”切换为“主动申请或关机返”。
-- 切换点以前的流水已经可能由旧 RebateService 发放，故将累计账户基线标为已领取，避免重复返。
UPDATE sangong_rebate_accounts
SET turnover_claimed=turnover_total,
    amount_claimed=GREATEST(amount_claimed, amount_total),
    updated_at=NOW()
WHERE turnover_claimed < turnover_total;
