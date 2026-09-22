CREATE TABLE IF NOT EXISTS moment_settings (
  user_id VARCHAR(64) NOT NULL,
  cover_url VARCHAR(1000) NULL,
  visible_range_days INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id)
);

CREATE TABLE IF NOT EXISTS moment_settings_blocked_viewer (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  blocked_user_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_blocked_viewer (user_id, blocked_user_id),
  KEY idx_moment_blocked_viewer_user (user_id)
);

CREATE TABLE IF NOT EXISTS moment_settings_hidden_author (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  hidden_user_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_hidden_author (user_id, hidden_user_id),
  KEY idx_moment_hidden_author_user (user_id)
);

CREATE TABLE IF NOT EXISTS moment_visible_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  moment_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_moment_visible_user (moment_id, user_id),
  KEY idx_moment_visible_user_moment (moment_id)
);
