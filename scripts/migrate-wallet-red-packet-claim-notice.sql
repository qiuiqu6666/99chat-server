-- 群红包领取通知 IM 消息幂等记录（定向 To_Account=发包人）
CREATE TABLE IF NOT EXISTS wallet_red_packet_claim_notice (
  id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  notice_id        VARCHAR(128) NOT NULL COMMENT 'rpcn_{packetId}_{claimerUserId}_{claimTsSec}',
  packet_id        BIGINT       NOT NULL,
  sender_user_id   VARCHAR(10)  NOT NULL,
  group_id         VARCHAR(64)  NOT NULL,
  claimer_user_id  VARCHAR(10)  NOT NULL,
  show_finished_suffix TINYINT(1) NOT NULL DEFAULT 0,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notice_id (notice_id),
  UNIQUE KEY uk_notice_packet_claimer (packet_id, claimer_user_id),
  KEY idx_notice_packet (packet_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
