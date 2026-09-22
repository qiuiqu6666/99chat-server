-- 群加群入口开关与申请来源（MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行前请确认列不存在）
ALTER TABLE group_settings
    ADD COLUMN allow_join_by_qr_code BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN allow_join_by_alias BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE group_join_application
    ADD COLUMN join_source VARCHAR(32) NULL;
