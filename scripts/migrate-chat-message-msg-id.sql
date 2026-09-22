-- Add Tencent MsgId column to all archived monthly chat_message_* tables.
-- Idempotent: skips tables that already have msg_id.

SET @schema := DATABASE();

DROP PROCEDURE IF EXISTS chat99_add_msg_id_columns;
DELIMITER $$
CREATE PROCEDURE chat99_add_msg_id_columns()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tbl VARCHAR(128);
  DECLARE cur CURSOR FOR
    SELECT DISTINCT physical_table
    FROM chat_message_table_registry
    WHERE physical_table IS NOT NULL AND physical_table LIKE 'chat_message_%'
    UNION
    SELECT TABLE_NAME
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @schema AND TABLE_NAME LIKE 'chat_message_20%';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO tbl;
    IF done = 1 THEN
      LEAVE read_loop;
    END IF;
    IF EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = tbl AND COLUMN_NAME = 'msg_id'
    ) THEN
      ITERATE read_loop;
    END IF;
    SET @ddl := CONCAT(
      'ALTER TABLE `', tbl, '` ',
      'ADD COLUMN msg_id VARCHAR(192) NULL AFTER msg_key, ',
      'ADD KEY idx_msg_id (msg_id)'
    );
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
END$$
DELIMITER ;

CALL chat99_add_msg_id_columns();
DROP PROCEDURE IF EXISTS chat99_add_msg_id_columns;
