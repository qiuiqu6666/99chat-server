-- Apply before deploying paid Community creation (JPA schema updates are disabled).
CREATE TABLE IF NOT EXISTS community_create_payment (
  group_id VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_user_id VARCHAR(32) NOT NULL,
  currency VARCHAR(16) NOT NULL,
  amount_minor BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_community_create_payment_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
