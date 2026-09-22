-- 聊天大附件：私有桶元数据、分片上传、引用授权与配额账本
-- 身份列使用 utf8mb4_bin，避免大小写折叠改变 userId / groupId 语义。

CREATE TABLE IF NOT EXISTS chat_attachment (
  attachment_id VARCHAR(48) NOT NULL,
  owner_user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  parent_attachment_id VARCHAR(48) NULL,
  storage_provider VARCHAR(32) NOT NULL,
  bucket VARCHAR(128) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  original_name VARCHAR(255) NULL,
  kind VARCHAR(16) NOT NULL,
  native_message_kind VARCHAR(16) NOT NULL,
  mime_type VARCHAR(128) NULL,
  declared_size_bytes BIGINT NOT NULL,
  size_bytes BIGINT NULL,
  declared_checksum_algorithm VARCHAR(32) NULL,
  declared_checksum VARCHAR(128) NULL,
  checksum_algorithm VARCHAR(32) NULL,
  checksum VARCHAR(128) NULL,
  checksum_status VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  duration_ms BIGINT NULL,
  width INT NULL,
  height INT NULL,
  thumbnail_attachment_id VARCHAR(48) NULL,
  metadata_provenance VARCHAR(16) NULL,
  media_probe_source VARCHAR(16) NULL,
  media_probed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  ready_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  purge_after DATETIME(3) NULL,
  PRIMARY KEY (attachment_id),
  KEY idx_chat_att_owner_status (owner_user_id, status),
  KEY idx_chat_att_status_expires (status, expires_at),
  KEY idx_chat_att_status_purge (status, purge_after),
  KEY idx_chat_att_parent (parent_attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_upload_session (
  upload_id VARCHAR(48) NOT NULL,
  attachment_id VARCHAR(48) NOT NULL,
  owner_user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  client_upload_key VARCHAR(128) NOT NULL,
  parent_upload_id VARCHAR(48) NULL,
  provider_upload_id VARCHAR(256) NULL,
  conversation_type VARCHAR(16) NOT NULL,
  conversation_key VARCHAR(160) NOT NULL,
  participant_low VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  participant_high VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  group_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  kind VARCHAR(16) NOT NULL,
  native_message_kind VARCHAR(16) NOT NULL,
  declared_size_bytes BIGINT NOT NULL,
  part_size_bytes BIGINT NOT NULL,
  expected_part_count INT NOT NULL,
  quota_day DATE NOT NULL,
  reserved_storage_bytes BIGINT NOT NULL,
  reserved_daily_bytes BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (upload_id),
  UNIQUE KEY uk_chat_upload_owner_key (owner_user_id, client_upload_key),
  KEY idx_chat_upload_attachment (attachment_id),
  KEY idx_chat_upload_status_exp (status, expires_at),
  KEY idx_chat_upload_parent (parent_upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_upload_part (
  upload_id VARCHAR(48) NOT NULL,
  part_number INT NOT NULL,
  size_bytes BIGINT NOT NULL,
  etag VARCHAR(128) NULL,
  confirmed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (upload_id, part_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_attachment_reference (
  reference_id VARCHAR(48) NOT NULL,
  attachment_id VARCHAR(48) NOT NULL,
  owner_user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  conversation_type VARCHAR(16) NOT NULL,
  conversation_key VARCHAR(160) NOT NULL,
  participant_low VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  participant_high VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  group_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  client_operation_id VARCHAR(128) NOT NULL,
  provider_message_id VARCHAR(128) NULL,
  reference_type VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  confirmed_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  PRIMARY KEY (reference_id),
  UNIQUE KEY uk_chat_ref_op (owner_user_id, client_operation_id, attachment_id),
  KEY idx_chat_ref_attachment_state (attachment_id, state),
  KEY idx_chat_ref_conv_state (conversation_key, state),
  KEY idx_chat_ref_state_exp (state, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_user_quota (
  user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  used_storage_bytes BIGINT NOT NULL DEFAULT 0,
  reserved_storage_bytes BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_user_daily_quota (
  user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  quota_day DATE NOT NULL,
  used_bytes BIGINT NOT NULL DEFAULT 0,
  reserved_bytes BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, quota_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS chat_attachment_device_capability (
  user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  device_id VARCHAR(64) NOT NULL,
  platform VARCHAR(16) NULL,
  app_version VARCHAR(32) NULL,
  app_version_code INT NULL,
  protocol_version INT NULL,
  capable TINYINT(1) NOT NULL DEFAULT 0,
  last_seen_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id, device_id),
  KEY idx_chat_cap_user (user_id, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
