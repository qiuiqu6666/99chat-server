-- 提现：单笔/每日上限 5000 USDT（micro=5_000_000_000），配合 WALLET_WITHDRAW_MODE=auto 限额内自动出链
-- 可重复执行

UPDATE wallet_limit_config
SET per_tx_max = 5000000000,
    daily_max = 5000000000,
    enabled = 1,
    updated_at = UTC_TIMESTAMP(3)
WHERE scene = 'WITHDRAW'
  AND currency = 'USDT';
