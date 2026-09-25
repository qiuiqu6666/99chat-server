-- Apply before deploying paid Community creation (JPA schema updates are disabled).
CREATE TABLE IF NOT EXISTS community_create_payment (
  group_id VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_user_id VARCHAR(32) NOT NULL,
  currency VARCHAR(16) NOT NULL,
  amount_minor BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_community_create_payment_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- wallet_ledger.ledger_type 是 MySQL ENUM；Java 新增 GROUP_CREATE / LIVE_TIP_* 后必须同步，否则扣费 Data truncated。
ALTER TABLE wallet_ledger
  MODIFY COLUMN ledger_type ENUM(
    'ADMIN_ADJUST',
    'DEPOSIT',
    'EXCHANGE_IN',
    'EXCHANGE_OUT',
    'EXCHANGE_SURPLUS',
    'FEE',
    'LIFE_PAYMENT',
    'RED_PACKET_RECEIVE',
    'RED_PACKET_REFUND',
    'RED_PACKET_SEND',
    'TRANSFER_IN',
    'TRANSFER_OUT',
    'WITHDRAW',
    'WITHDRAW_REFUND',
    'LIVE_TIP_OUT',
    'LIVE_TIP_IN',
    'GROUP_CREATE'
  ) NOT NULL;
