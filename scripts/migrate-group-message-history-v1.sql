-- Super-group history schema reconcile v1.
-- Run against the intended database during a maintenance window.
-- This script only adds missing indexes; it never deletes or rewrites messages.
-- Run audit-group-message-history.sql first and resolve duplicate data manually.

SET @db := DATABASE();

DROP PROCEDURE IF EXISTS reconcile_group_message_history_indexes;
DELIMITER $$
CREATE PROCEDURE reconcile_group_message_history_indexes()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tbl VARCHAR(128);
  DECLARE tables_cur CURSOR FOR
    SELECT table_name
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name LIKE 'chat_message_%'
      AND table_type = 'BASE TABLE';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  OPEN tables_cur;
  table_loop: LOOP
    FETCH tables_cur INTO tbl;
    IF done = 1 THEN LEAVE table_loop; END IF;

    SET @has_index := (
      SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = tbl
        AND index_name = 'idx_group_message_history'
    );
    IF @has_index = 0 THEN
      SET @ddl := CONCAT(
        'ALTER TABLE `', REPLACE(tbl, '`', '``'),
        '` ADD INDEX `idx_group_message_history` (chat_type, group_id, msg_seq)'
      );
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
    END IF;
  END LOOP;
  CLOSE tables_cur;
END$$
DELIMITER ;

CALL reconcile_group_message_history_indexes();
DROP PROCEDURE IF EXISTS reconcile_group_message_history_indexes;

-- Deliberately not automatic:
-- UNIQUE(group_id, msg_seq) and UNIQUE(message_id) require duplicate/null audits
-- and a product decision for legacy rows before they can be safely added.
