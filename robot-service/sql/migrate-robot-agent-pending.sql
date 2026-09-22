-- 机器人快照 / 日归档：代理待结算流水、待结算反水（由机器人计算后同步；0 为有效值）
-- 若列已存在会报错，可忽略对应语句。

ALTER TABLE robot_player_snapshot
  ADD COLUMN agent_pending_flow DECIMAL(20,4) NULL AFTER remaining_flow,
  ADD COLUMN agent_pending_rebate DECIMAL(20,4) NULL AFTER agent_pending_flow;

ALTER TABLE robot_player_daily_summary
  ADD COLUMN agent_pending_flow DECIMAL(20,4) NOT NULL DEFAULT 0.0000 AFTER remaining_flow,
  ADD COLUMN agent_pending_rebate DECIMAL(20,4) NOT NULL DEFAULT 0.0000 AFTER agent_pending_flow;
