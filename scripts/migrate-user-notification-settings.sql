-- 用户通知偏好：系统消息开关、音视频来电开关、通知栏展示内容
ALTER TABLE users
    ADD COLUMN system_message_notification_enabled TINYINT(1) NOT NULL DEFAULT 1;

ALTER TABLE users
    ADD COLUMN call_notification_enabled TINYINT(1) NOT NULL DEFAULT 1;

ALTER TABLE users
    ADD COLUMN notification_display_content VARCHAR(16) NOT NULL DEFAULT 'show_all';
