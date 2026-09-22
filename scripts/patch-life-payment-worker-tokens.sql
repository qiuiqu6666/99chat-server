-- 增量补丁：为 life_payment_worker_devices 增加设备专用 token 哈希列
-- 用法：mysql -u chat99 -p chat99 < scripts/patch-life-payment-worker-tokens.sql

ALTER TABLE life_payment_worker_devices
  ADD COLUMN worker_token_hash VARCHAR(64) NULL AFTER remark;

ALTER TABLE life_payment_worker_devices
  ADD UNIQUE KEY uk_lp_worker_token_hash (worker_token_hash);
