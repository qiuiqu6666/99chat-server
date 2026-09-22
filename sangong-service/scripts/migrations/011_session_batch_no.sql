SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_sessions' AND COLUMN_NAME='batch_no');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_sessions ADD COLUMN batch_no VARCHAR(16) NULL AFTER business_date',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE sangong_sessions s
JOIN (
  SELECT id, CONCAT(DATE_FORMAT(COALESCE(business_date, DATE(started_at)), '%Y%m%d'),
    LPAD(ROW_NUMBER() OVER (
      PARTITION BY tenant_id, COALESCE(business_date, DATE(started_at)) ORDER BY id
    ), 2, '0')) AS generated_batch_no
  FROM sangong_sessions
) numbered ON numbered.id=s.id
SET s.batch_no=numbered.generated_batch_no
WHERE s.batch_no IS NULL OR s.batch_no='';

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_sessions' AND INDEX_NAME='uk_sessions_tenant_batch_no');
SET @sql := IF(@idx=0,
  'ALTER TABLE sangong_sessions ADD UNIQUE KEY uk_sessions_tenant_batch_no (tenant_id, batch_no)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
