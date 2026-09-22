-- 业务日报改为按开机/关机批次（session）归属；保留 stat_date 兼容旧查询。

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_sessions' AND COLUMN_NAME='business_date');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_sessions ADD COLUMN business_date DATE NULL AFTER stopped_at, ADD KEY idx_sessions_tenant_business (tenant_id, business_date, id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_sessions SET business_date=DATE(started_at) WHERE business_date IS NULL;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_rebate_turnover' AND COLUMN_NAME='session_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_rebate_turnover ADD COLUMN session_id BIGINT UNSIGNED NULL AFTER tenant_id, ADD KEY idx_turnover_tenant_session_user (tenant_id, session_id, user_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_rebate_turnover rt
JOIN sangong_sessions ss ON ss.tenant_id=rt.tenant_id AND ss.business_date=rt.stat_date
SET rt.session_id=ss.id
WHERE rt.session_id IS NULL
  AND ss.id=(SELECT MAX(s2.id) FROM sangong_sessions s2
             WHERE s2.tenant_id=rt.tenant_id AND s2.business_date=rt.stat_date);

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_rebate_turnover' AND INDEX_NAME='uk_turnover_tenant_date_user');
SET @sql := IF(@idx>0, 'ALTER TABLE sangong_rebate_turnover DROP INDEX uk_turnover_tenant_date_user', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_rebate_turnover' AND INDEX_NAME='uk_turnover_tenant_session_user');
SET @sql := IF(@idx=0,
  'ALTER TABLE sangong_rebate_turnover ADD UNIQUE KEY uk_turnover_tenant_session_user (tenant_id, session_id, user_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_rebate_turnover_events' AND COLUMN_NAME='session_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_rebate_turnover_events ADD COLUMN session_id BIGINT UNSIGNED NULL AFTER tenant_id, ADD KEY idx_turnover_events_session (tenant_id, session_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_rebate_turnover_events e
JOIN sangong_rounds r ON r.id=e.round_id AND r.tenant_id=e.tenant_id
SET e.session_id=r.session_id WHERE e.session_id IS NULL;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_rebate_ledger' AND COLUMN_NAME='session_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_rebate_ledger ADD COLUMN session_id BIGINT UNSIGNED NULL AFTER tenant_id, ADD KEY idx_rebate_ledger_session (tenant_id, session_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_rebate_ledger rl
JOIN sangong_rounds r ON r.id=rl.source_round_id AND r.tenant_id=rl.tenant_id
SET rl.session_id=r.session_id WHERE rl.session_id IS NULL;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_agent_ledger' AND COLUMN_NAME='session_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_agent_ledger ADD COLUMN session_id BIGINT UNSIGNED NULL AFTER tenant_id, ADD KEY idx_agent_ledger_session (tenant_id, session_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_agent_ledger al
JOIN sangong_rounds r ON al.ref_type='round' AND r.id=al.ref_id AND r.tenant_id=al.tenant_id
SET al.session_id=r.session_id WHERE al.session_id IS NULL;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_user_transfers' AND COLUMN_NAME='session_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_user_transfers ADD COLUMN session_id BIGINT UNSIGNED NULL AFTER tenant_id, ADD KEY idx_user_transfers_session (tenant_id, session_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
