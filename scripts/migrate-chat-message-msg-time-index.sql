-- chat_message 月表补 msg_time_ms 单列索引（在线 DDL，不锁表）。
-- 背景：CallAvCallIncrementalMergeJob 每 30s 执行
--   ... WHERE msg_body_json LIKE '%av_call%' AND msg_time_ms >= ? ORDER BY msg_time_ms
-- 原索引均以 chat_type 打头，无法命中，EXPLAIN 为 type=ALL（~100 万行 + filesort，慢日志 3~7s）。
-- 新月表由 ChatMessageWriteRepository.ensureTable 自动带 idx_msg_time；本脚本只补存量表。
-- 若某表索引已存在会报 Duplicate key name，可忽略继续。

ALTER TABLE chat_message_202606 ADD INDEX idx_msg_time (msg_time_ms), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE chat_message_202607 ADD INDEX idx_msg_time (msg_time_ms), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE chat_message_202608 ADD INDEX idx_msg_time (msg_time_ms), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE chat_message_202609 ADD INDEX idx_msg_time (msg_time_ms), ALGORITHM=INPLACE, LOCK=NONE;
