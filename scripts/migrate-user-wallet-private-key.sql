-- 用户收款地址私钥落库（HD 派生 hex，无 0x 前缀）
ALTER TABLE user_wallet
    ADD COLUMN tron_private_key VARCHAR(64) NULL AFTER tron_address;
