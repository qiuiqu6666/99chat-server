-- 删除指定测试用户及其关联数据（保留 99Chat / 99Messenger）
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS delete_app_users;
DELIMITER //
CREATE PROCEDURE delete_app_users(IN p_user_ids TEXT)
BEGIN
  CREATE TEMPORARY TABLE IF NOT EXISTS tmp_del_uids (user_id VARCHAR(10) PRIMARY KEY);
  TRUNCATE tmp_del_uids;
  SET @sql = CONCAT(
    'INSERT INTO tmp_del_uids(user_id) SELECT user_id FROM users WHERE user_id IN (''',
    REPLACE(p_user_ids, ',', ''','''),
    ''') AND user_id NOT IN (''99Chat'',''99Messenger'')');
  PREPARE stmt FROM @sql;
  EXECUTE stmt;
  DEALLOCATE PREPARE stmt;

  DELETE FROM wallet_red_packet_claim WHERE user_id IN (SELECT user_id FROM tmp_del_uids)
     OR packet_id IN (SELECT id FROM wallet_red_packet WHERE sender_user_id IN (SELECT user_id FROM tmp_del_uids));
  DELETE FROM wallet_red_packet WHERE sender_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM wallet_ledger WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM wallet_deposit WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM wallet_withdrawal WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM wallet_exchange_order WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM wallet_transfer WHERE from_user_id IN (SELECT user_id FROM tmp_del_uids)
     OR to_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM wallet_sweep_log WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_wallet WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM friend_application_history WHERE user_id IN (SELECT user_id FROM tmp_del_uids)
     OR peer_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_starred_friend WHERE user_id IN (SELECT user_id FROM tmp_del_uids)
     OR friend_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_friend WHERE user_id IN (SELECT user_id FROM tmp_del_uids)
     OR friend_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_message_favorite WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_feedback WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_sticker_favorite WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_sticker_pack WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_conversation_notify WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_push_token WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM call_record_user WHERE user_id IN (SELECT user_id FROM tmp_del_uids)
     OR peer_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM call_session WHERE caller_user_id IN (SELECT user_id FROM tmp_del_uids)
     OR callee_user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_photo_upload WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_photo WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_contact_item WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM sync_session WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_sync_state WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM login_log WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM user_device WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM announcement_read WHERE user_id IN (SELECT user_id FROM tmp_del_uids);
  DELETE FROM users WHERE user_id IN (SELECT user_id FROM tmp_del_uids);

  SELECT COUNT(*) AS deleted_users FROM tmp_del_uids;
END//
DELIMITER ;

-- 删除用户列出的 5 个 + 同批次其余账号（用户300975001～031）
CALL delete_app_users((
  SELECT GROUP_CONCAT(user_id SEPARATOR ',')
  FROM users
  WHERE nickname LIKE '用户300975%'
     OR user_id IN ('favu57v0g9','vaq0sm2440','vj9efeesh6','xyco081iqo','mfwfuwohuv','rqwm8onw3j')
));

DROP PROCEDURE IF EXISTS delete_app_users;
