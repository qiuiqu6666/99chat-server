-- Run after migrate-wallet-card-state.sql and before deploying the new worker.
ALTER TABLE wallet_card_outbox ADD COLUMN reconcile_cursor VARCHAR(256) NULL;
CREATE INDEX idx_wallet_card_conversation ON wallet_card_outbox(group_message, target_id, created_at, id);
CREATE INDEX idx_wallet_card_sender ON wallet_card_outbox(sender_id, target_id, created_at, id);
-- Existing REVIEW rows are picked up automatically; never reset them to PENDING.
UPDATE wallet_card_outbox
SET state = 'RECONCILING', next_attempt_at = CURRENT_TIMESTAMP,
    last_error = 'MESSAGE_LOCATOR_MISSING'
WHERE state = 'SENT' AND first_attempt_at IS NOT NULL
  AND ((group_message = TRUE AND (message_seq IS NULL OR message_seq <= 0))
    OR (group_message = FALSE AND (message_key IS NULL OR message_key = '')));
