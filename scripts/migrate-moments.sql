CREATE TABLE IF NOT EXISTS moment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  moment_id VARCHAR(64) NOT NULL,
  author_user_id VARCHAR(64) NOT NULL,
  text VARCHAR(2000) NULL,
  location VARCHAR(100) NULL,
  visibility VARCHAR(20) NOT NULL,
  status INT NOT NULL DEFAULT 1,
  idempotency_key VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_id (moment_id),
  UNIQUE KEY uk_moment_author_idempotency (author_user_id, idempotency_key),
  KEY idx_moment_feed (author_user_id, status, created_at),
  KEY idx_moment_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS moment_media (
  id BIGINT NOT NULL AUTO_INCREMENT,
  media_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  moment_id VARCHAR(64) NULL,
  type VARCHAR(20) NOT NULL,
  url VARCHAR(1000) NOT NULL,
  thumb_url VARCHAR(1000) NULL,
  object_key VARCHAR(500) NULL,
  thumb_object_key VARCHAR(500) NULL,
  width INT NULL,
  height INT NULL,
  duration_sec INT NULL,
  size_bytes BIGINT NULL,
  client_media_id VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_media_id (media_id),
  UNIQUE KEY uk_moment_media_client (owner_user_id, client_media_id),
  KEY idx_moment_media_owner (owner_user_id, moment_id),
  KEY idx_moment_media_moment (moment_id)
);

CREATE TABLE IF NOT EXISTS moment_like (
  id BIGINT NOT NULL AUTO_INCREMENT,
  moment_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_like_user (moment_id, user_id),
  KEY idx_moment_like_moment (moment_id, created_at),
  KEY idx_moment_like_user (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS moment_comment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  comment_id VARCHAR(64) NOT NULL,
  moment_id VARCHAR(64) NOT NULL,
  author_user_id VARCHAR(64) NOT NULL,
  reply_to_comment_id VARCHAR(64) NULL,
  reply_to_user_id VARCHAR(64) NULL,
  text VARCHAR(1000) NOT NULL,
  status INT NOT NULL DEFAULT 1,
  idempotency_key VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_comment_id (comment_id),
  UNIQUE KEY uk_moment_comment_idempotency (moment_id, author_user_id, idempotency_key),
  KEY idx_moment_comment_moment (moment_id, status, created_at),
  KEY idx_moment_comment_author (author_user_id, created_at)
);

CREATE TABLE IF NOT EXISTS moment_notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  notification_id VARCHAR(64) NOT NULL,
  recipient_user_id VARCHAR(64) NOT NULL,
  type VARCHAR(30) NOT NULL,
  actor_user_id VARCHAR(64) NOT NULL,
  moment_id VARCHAR(64) NOT NULL,
  comment_id VARCHAR(64) NULL,
  reply_to_user_id VARCHAR(64) NULL,
  read_flag BIT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_notification_id (notification_id),
  KEY idx_moment_notification_recipient (recipient_user_id, created_at),
  KEY idx_moment_notification_unread (recipient_user_id, read_flag)
);
