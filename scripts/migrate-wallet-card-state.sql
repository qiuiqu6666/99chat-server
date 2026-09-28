-- Run AFTER migrate-wallet-card-outbox.sql and BEFORE deploying this version.
-- One-time MySQL 8 migration, including installations of the original outbox.
ALTER TABLE wallet_card_outbox
  ADD COLUMN message_seq bigint NULL,
  ADD COLUMN next_sync_at datetime(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
  ADD COLUMN last_sync_at datetime(6) NULL,
  ADD COLUMN synced_payload text NULL,
  ADD COLUMN sync_error varchar(64) NULL,
  ADD KEY idx_wallet_card_sync (state, next_sync_at);
-- Existing sent group rows without message_seq need the authenticated AfterSend
-- receipt or an operator-verified history lookup. Never resend to invent a locator.
