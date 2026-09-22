-- Super-group history schema audit. Read-only except temporary variables.
-- No data is deleted or modified by this script.
SET @db := DATABASE();

SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = @db AND table_name LIKE 'chat_message_%'
ORDER BY table_name;

SELECT table_name, column_name, column_type, is_nullable, column_key
FROM information_schema.columns
WHERE table_schema = @db AND table_name LIKE 'chat_message_%'
  AND column_name IN ('group_id', 'msg_seq', 'msg_id', 'msg_key', 'msg_time_ms', 'status')
ORDER BY table_name, ordinal_position;

SELECT table_name, index_name,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns,
       non_unique
FROM information_schema.statistics
WHERE table_schema = @db AND table_name LIKE 'chat_message_%'
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;

-- Duplicate logical group sequence audit. A result requires manual review.
SET @sql := (
  SELECT GROUP_CONCAT(CONCAT(
    'SELECT ''', table_name, ''' AS table_name, group_id, msg_seq, COUNT(*) AS duplicate_count ',
    'FROM `', table_name, '` WHERE chat_type=1 AND msg_seq IS NOT NULL ',
    'GROUP BY group_id, msg_seq HAVING COUNT(*) > 1'
  ) SEPARATOR ' UNION ALL ')
  FROM information_schema.tables
  WHERE table_schema = @db AND table_name LIKE 'chat_message_%'
);
SET @sql := IFNULL(@sql, 'SELECT NULL AS table_name, NULL AS group_id, NULL AS msg_seq, 0 AS duplicate_count WHERE 1=0');
PREPARE audit_stmt FROM @sql;
EXECUTE audit_stmt;
DEALLOCATE PREPARE audit_stmt;

-- Missing sequence values cannot be inferred safely from SQL alone; the application
-- reports only explainable gaps and fails closed for unexplained gaps.
