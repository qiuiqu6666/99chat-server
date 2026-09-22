-- 生活缴费：订单 / 明细 / 查询 / 任务队列 / 档案 / 缴费单位 / 面额 / Worker / 日志
-- 生产建议手动执行；JPA ddl-auto=update 也会自动建表。

CREATE TABLE IF NOT EXISTS life_payment_orders (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  client_order_id VARCHAR(128) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  service_type VARCHAR(20) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  pay_method VARCHAR(20) NOT NULL,
  platform_pay_status VARCHAR(20) NOT NULL,
  plugin_status VARCHAR(40) NOT NULL,
  order_status VARCHAR(40) NOT NULL,
  query_no VARCHAR(64) NULL,
  paid_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_order_no (order_no),
  UNIQUE KEY uk_lp_client_order (user_id, client_order_id),
  KEY idx_lp_orders_user_created (user_id, created_at),
  KEY idx_lp_orders_status (order_status, created_at),
  KEY idx_lp_orders_service (service_type, created_at)
);

CREATE TABLE IF NOT EXISTS life_payment_mobile_details (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  owner_last_char VARCHAR(8) NULL,
  is_first_recharge TINYINT(1) NOT NULL DEFAULT 0,
  carrier_name VARCHAR(64) NULL,
  recharge_status VARCHAR(64) NULL,
  receipt VARCHAR(500) NULL,
  alipay_trade_no VARCHAR(128) NULL,
  paid_amount DECIMAL(12,2) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_mobile_order (order_no),
  KEY idx_lp_mobile_phone (phone)
);

CREATE TABLE IF NOT EXISTS life_payment_utility_details (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  service_type VARCHAR(20) NOT NULL,
  city_name VARCHAR(64) NOT NULL,
  city_code VARCHAR(32) NULL,
  provider_name VARCHAR(128) NOT NULL,
  provider_code VARCHAR(64) NULL,
  account_no VARCHAR(64) NOT NULL,
  user_address VARCHAR(500) NULL,
  account_balance VARCHAR(128) NULL,
  utility_status VARCHAR(64) NULL,
  receipt VARCHAR(500) NULL,
  alipay_trade_no VARCHAR(128) NULL,
  paid_amount DECIMAL(12,2) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_utility_order (order_no),
  KEY idx_lp_utility_account (service_type, account_no)
);

CREATE TABLE IF NOT EXISTS life_payment_utility_queries (
  id BIGINT NOT NULL AUTO_INCREMENT,
  query_no VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  service_type VARCHAR(20) NOT NULL,
  city_name VARCHAR(64) NOT NULL,
  city_code VARCHAR(32) NULL,
  provider_name VARCHAR(128) NOT NULL,
  provider_code VARCHAR(64) NULL,
  account_no VARCHAR(64) NOT NULL,
  user_address VARCHAR(500) NULL,
  account_balance VARCHAR(128) NULL,
  suggest_amount VARCHAR(32) NULL,
  query_status VARCHAR(40) NOT NULL,
  plugin_status VARCHAR(40) NULL,
  receipt VARCHAR(500) NULL,
  expired_at DATETIME(6) NULL,
  confirmed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_query_no (query_no),
  KEY idx_lp_query_user (user_id, created_at),
  KEY idx_lp_query_account (service_type, account_no)
);

CREATE TABLE IF NOT EXISTS life_payment_accounts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  service_type VARCHAR(20) NOT NULL,
  account_no VARCHAR(64) NOT NULL,
  city_name VARCHAR(64) NULL,
  city_code VARCHAR(32) NULL,
  provider_name VARCHAR(128) NULL,
  provider_code VARCHAR(64) NULL,
  owner_last_char VARCHAR(8) NULL,
  user_address VARCHAR(500) NULL,
  verified TINYINT(1) NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  first_verified_at DATETIME(6) NULL,
  last_paid_at DATETIME(6) NULL,
  last_order_no VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_account (service_type, account_no, city_code, provider_code),
  KEY idx_lp_account_no (service_type, account_no)
);

CREATE TABLE IF NOT EXISTS life_payment_providers (
  id BIGINT NOT NULL AUTO_INCREMENT,
  service_type VARCHAR(20) NOT NULL,
  country_code VARCHAR(8) NOT NULL DEFAULT 'CN',
  province_name VARCHAR(64) NULL,
  city_name VARCHAR(64) NOT NULL,
  city_code VARCHAR(32) NULL,
  provider_name VARCHAR(128) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  provider_alias VARCHAR(128) NULL,
  source VARCHAR(64) NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  last_captured_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_provider_code (provider_code),
  UNIQUE KEY uk_lp_provider_city_name (service_type, city_name, provider_name),
  KEY idx_lp_provider_city (service_type, city_name, enabled)
);

CREATE TABLE IF NOT EXISTS life_payment_amount_options (
  id BIGINT NOT NULL AUTO_INCREMENT,
  service_type VARCHAR(20) NOT NULL,
  country_code VARCHAR(8) NOT NULL DEFAULT 'CN',
  operator_name VARCHAR(64) NULL,
  city_code VARCHAR(32) NULL,
  provider_code VARCHAR(64) NULL,
  amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lp_amount_service (service_type, enabled, sort_order)
);

CREATE TABLE IF NOT EXISTS life_payment_task_queue (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NULL,
  query_no VARCHAR(64) NULL,
  service_type VARCHAR(20) NOT NULL,
  task_action VARCHAR(20) NOT NULL,
  payment_status VARCHAR(20) NOT NULL DEFAULT 'none',
  payload_json TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  locked_by VARCHAR(64) NULL,
  locked_at DATETIME(6) NULL,
  heartbeat_at DATETIME(6) NULL,
  last_error_code VARCHAR(64) NULL,
  last_error_message VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_task_no (task_no),
  KEY idx_lp_task_claim (status, service_type, attempt_count, created_at),
  KEY idx_lp_task_order (order_no),
  KEY idx_lp_task_query (query_no),
  KEY idx_lp_task_heartbeat (status, heartbeat_at)
);

CREATE TABLE IF NOT EXISTS life_payment_worker_devices (
  id BIGINT NOT NULL AUTO_INCREMENT,
  worker_id VARCHAR(64) NOT NULL,
  device_id VARCHAR(128) NULL,
  device_name VARCHAR(128) NULL,
  support_service_types VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL,
  last_online_at DATETIME(6) NULL,
  last_heartbeat_at DATETIME(6) NULL,
  last_offline_at DATETIME(6) NULL,
  app_version VARCHAR(32) NULL,
  remark VARCHAR(255) NULL,
  worker_token_hash VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lp_worker_id (worker_id),
  UNIQUE KEY uk_lp_worker_token_hash (worker_token_hash),
  KEY idx_lp_worker_status (status, last_heartbeat_at)
);

CREATE TABLE IF NOT EXISTS life_payment_operation_logs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) NULL,
  task_no VARCHAR(64) NULL,
  service_type VARCHAR(20) NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor_id VARCHAR(64) NULL,
  action VARCHAR(64) NOT NULL,
  message VARCHAR(500) NULL,
  request_json TEXT NULL,
  response_json TEXT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lp_log_order (order_no, created_at),
  KEY idx_lp_log_task (task_no, created_at)
);

-- 默认手机充值面额
INSERT INTO life_payment_amount_options
  (service_type, country_code, operator_name, city_code, provider_code, amount, currency, enabled, sort_order, created_at, updated_at)
SELECT 'mobile', 'CN', NULL, NULL, NULL, 30.00, 'CNY', 1, 10, NOW(6), NOW(6)
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM life_payment_amount_options WHERE service_type='mobile' AND amount=30.00 AND operator_name IS NULL
);
INSERT INTO life_payment_amount_options
  (service_type, country_code, operator_name, city_code, provider_code, amount, currency, enabled, sort_order, created_at, updated_at)
SELECT 'mobile', 'CN', NULL, NULL, NULL, 50.00, 'CNY', 1, 20, NOW(6), NOW(6)
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM life_payment_amount_options WHERE service_type='mobile' AND amount=50.00 AND operator_name IS NULL
);
INSERT INTO life_payment_amount_options
  (service_type, country_code, operator_name, city_code, provider_code, amount, currency, enabled, sort_order, created_at, updated_at)
SELECT 'mobile', 'CN', NULL, NULL, NULL, 100.00, 'CNY', 1, 30, NOW(6), NOW(6)
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM life_payment_amount_options WHERE service_type='mobile' AND amount=100.00 AND operator_name IS NULL
);
INSERT INTO life_payment_amount_options
  (service_type, country_code, operator_name, city_code, provider_code, amount, currency, enabled, sort_order, created_at, updated_at)
SELECT 'mobile', 'CN', NULL, NULL, NULL, 200.00, 'CNY', 1, 40, NOW(6), NOW(6)
FROM DUAL WHERE NOT EXISTS (
  SELECT 1 FROM life_payment_amount_options WHERE service_type='mobile' AND amount=200.00 AND operator_name IS NULL
);

-- 默认水电燃常用金额
INSERT INTO life_payment_amount_options
  (service_type, country_code, operator_name, city_code, provider_code, amount, currency, enabled, sort_order, created_at, updated_at)
SELECT st, 'CN', NULL, NULL, NULL, amt, 'CNY', 1, so, NOW(6), NOW(6)
FROM (
  SELECT 'water' AS st, 20.00 AS amt, 10 AS so UNION ALL
  SELECT 'water', 50.00, 20 UNION ALL
  SELECT 'water', 100.00, 30 UNION ALL
  SELECT 'electric', 20.00, 10 UNION ALL
  SELECT 'electric', 50.00, 20 UNION ALL
  SELECT 'electric', 100.00, 30 UNION ALL
  SELECT 'gas', 20.00, 10 UNION ALL
  SELECT 'gas', 50.00, 20 UNION ALL
  SELECT 'gas', 100.00, 30
) t
WHERE NOT EXISTS (
  SELECT 1 FROM life_payment_amount_options o
  WHERE o.service_type = t.st AND o.amount = t.amt AND o.city_code IS NULL AND o.provider_code IS NULL
);

-- Fix provider uniqueness: city_code was often empty, so use city_name.
-- Safe to re-run: only alters when old index definition is present.
SET @idx_cols := (
  SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'life_payment_providers'
    AND index_name = 'uk_lp_provider_city_name'
);
SET @sql := IF(
  @idx_cols IS NOT NULL AND @idx_cols <> 'service_type,city_name,provider_name',
  'ALTER TABLE life_payment_providers DROP INDEX uk_lp_provider_city_name, ADD UNIQUE KEY uk_lp_provider_city_name (service_type, city_name, provider_name)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
