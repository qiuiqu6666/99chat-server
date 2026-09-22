-- 闪兑开关：1=开放，0=维护（默认开，避免上线后突然全员维护）
ALTER TABLE wallet_exchange_config
  ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1
  COMMENT '闪兑开关：1开 0维护'
  AFTER min_withdraw_usdt_micro;
