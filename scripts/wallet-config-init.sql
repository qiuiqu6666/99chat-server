-- Wallet Config Initialization Script
-- Run this SQL to initialize wallet configuration in database

-- Insert default wallet configuration
INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_mnemonic', 'velvet mass very success nature pass blossom air juice world hammer glad', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.trongrid_api_key', '52b135ec-81e5-4a16-9356-4189c8eccc74', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.trongrid_base_url', 'https://api.trongrid.io', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.usdt_contract', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_mode', 'address-poll', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.min_deposit_usdt_micro', '1000000', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_confirmations', '19', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_hot_ttl_minutes', '120', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.trongrid_qps_limit', '6', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_scan_concurrency', '3', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_block_scan_batch_size', '20', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.pay_pin_max_failures', '5', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.pay_pin_lock_minutes', '30', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.red_packet_expire_hours', '24', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.exchange_rate_cache_seconds', '300', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.frankfurter_url', 'https://api.frankfurter.dev/v1/latest?from=USD&to=CNY', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_cold_batch_size', '200', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_cold_lookback_days', '3', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.deposit_scan_max_round_ms', '60000', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

-- Hot wallet private key (leave empty if not configured)
INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('wallet.hot_wallet_private_key', '', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();
