-- Apply before enabling /wallet/card-orders on every wallet/IM callback instance.
-- MySQL 8; deliberately no historical-order backfill (legacy clients own those cards).
CREATE TABLE IF NOT EXISTS wallet_card_outbox (
  id varchar(80) NOT NULL PRIMARY KEY,
  sender_id varchar(64) NOT NULL,
  client_id varchar(64) NOT NULL,
  target_id varchar(128) NOT NULL,
  group_message boolean NOT NULL,
  request_hash varchar(64) NOT NULL,
  payload text NOT NULL,
  im_random int NOT NULL,
  state varchar(16) NOT NULL,
  attempts int NOT NULL DEFAULT 0,
  created_at datetime(6) NOT NULL,
  first_attempt_at datetime(6) NULL,
  next_attempt_at datetime(6) NOT NULL,
  lease_token varchar(36) NULL,
  message_key varchar(128) NULL,
  last_error varchar(64) NULL,
  UNIQUE KEY uk_wallet_card_client (sender_id, client_id),
  KEY idx_wallet_card_due (state, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
