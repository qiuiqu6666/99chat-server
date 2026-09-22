-- latest 补充逆地理字段
ALTER TABLE user_location_latest
  ADD COLUMN country VARCHAR(64) NULL AFTER geohash,
  ADD COLUMN province VARCHAR(64) NULL AFTER country,
  ADD COLUMN city VARCHAR(64) NULL AFTER province,
  ADD COLUMN district VARCHAR(64) NULL AFTER city,
  ADD COLUMN city_label VARCHAR(128) NULL AFTER district;

CREATE TABLE IF NOT EXISTS user_location_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(10) NOT NULL,
  device_id VARCHAR(64) NULL,
  latitude DOUBLE NOT NULL,
  longitude DOUBLE NOT NULL,
  accuracy DOUBLE NULL,
  altitude DOUBLE NULL,
  heading DOUBLE NULL,
  speed DOUBLE NULL,
  source VARCHAR(16) NULL,
  geohash VARCHAR(16) NULL,
  country VARCHAR(64) NULL,
  province VARCHAR(64) NULL,
  city VARCHAR(64) NULL,
  district VARCHAR(64) NULL,
  city_label VARCHAR(128) NULL,
  collected_at DATETIME(6) NOT NULL,
  server_received_at DATETIME(6) NOT NULL,
  ip VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ulh_user_collected (user_id, collected_at),
  KEY idx_ulh_collected (collected_at)
);
