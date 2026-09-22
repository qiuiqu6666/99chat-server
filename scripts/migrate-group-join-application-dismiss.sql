-- 群通知：用户侧「删除」仅对自己隐藏审批记录
CREATE TABLE IF NOT EXISTS group_join_application_dismiss (
  user_id         VARCHAR(10)  NOT NULL,
  application_id  BIGINT       NOT NULL,
  dismissed_at    BIGINT       NOT NULL,
  PRIMARY KEY (user_id, application_id),
  KEY idx_gjad_application (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
