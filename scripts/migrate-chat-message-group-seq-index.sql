-- 为已有月分表补充群消息 seq 区间查询索引
-- 执行：mysql -u... -p... chat99 < scripts/migrate-chat-message-group-seq-index.sql

DROP PROCEDURE IF EXISTS add_idx_group_seq_all;
DELIMITER //
CREATE PROCEDURE add_idx_group_seq_all()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tbl VARCHAR(64);
  DECLARE cur CURSOR FOR SELECT physical_table FROM chat_message_table_registry;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  DECLARE CONTINUE HANDLER FOR 1061 BEGIN END; -- Duplicate key name

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO tbl;
    IF done THEN
      LEAVE read_loop;
    END IF;
    SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD KEY idx_group_seq (chat_type, group_id, msg_seq)');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
END //
DELIMITER ;

CALL add_idx_group_seq_all();
DROP PROCEDURE IF EXISTS add_idx_group_seq_all;
