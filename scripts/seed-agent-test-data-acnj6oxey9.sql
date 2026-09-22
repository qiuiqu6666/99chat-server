-- 为代理测试账号 acnj6oxey9 补充下级树与历史明细
-- 群：@25EFG6M5CC
--
-- 树结构：
-- acnj6oxey9 (一级代理)
-- ├── w4ajsj6p3a (直属玩家)
-- └── wo356xnswd (二级代理)
--     └── mfwfuwohuv (二级代理的下级玩家)

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

SET @group_id = '@25EFG6M5CC';
SET @stats_id = '@2ZIHG6M5CO';
SET @ts = UNIX_TIMESTAMP();

-- 1. 修正代理本人（去掉自引用）
UPDATE robot_player_snapshot
SET direct_parent_wxid = '',
    direct_parent_no = '',
    parent_path = '',
    level_no = 1,
    balance = 10000.0000,
    total_flow = 8000.0000,
    used_flow = 5000.0000,
    remaining_flow = 3000.0000,
    total_up = 5000.0000,
    total_down = 2000.0000,
    total_profit_loss = -500.0000,
    rebate_rate = 300.0000,
    total_rebate = 150.0000,
    source_updated_at = @ts,
    last_event_id = CONCAT(@group_id, '_test_seed_acnj6oxey9_', @ts),
    last_sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'acnj6oxey9';

-- 2. 直属玩家
UPDATE robot_player_snapshot
SET direct_parent_wxid = 'acnj6oxey9',
    direct_parent_no = '7583',
    parent_path = 'acnj6oxey9###',
    level_no = 2,
    balance = 5436.0000,
    total_flow = 5000.0000,
    used_flow = 3800.0000,
    remaining_flow = 1200.0000,
    total_up = 2000.0000,
    total_down = 500.0000,
    total_profit_loss = 200.0000,
    rebate_rate = 180.0000,
    total_rebate = 60.0000,
    source_updated_at = @ts,
    last_event_id = CONCAT(@group_id, '_test_seed_w4ajsj6p3a_', @ts),
    last_sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'w4ajsj6p3a';

-- 3. 二级代理（直属下级，且自身还有下级）
UPDATE robot_player_snapshot
SET direct_parent_wxid = 'acnj6oxey9',
    direct_parent_no = '7583',
    parent_path = 'acnj6oxey9###',
    level_no = 2,
    balance = 8000.0000,
    total_flow = 6000.0000,
    used_flow = 4000.0000,
    remaining_flow = 2000.0000,
    total_up = 3000.0000,
    total_down = 1000.0000,
    total_profit_loss = -300.0000,
    rebate_rate = 250.0000,
    total_rebate = 80.0000,
    source_updated_at = @ts,
    last_event_id = CONCAT(@group_id, '_test_seed_wo356xnswd_', @ts),
    last_sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'wo356xnswd';

-- 4. 二级代理的下级玩家
UPDATE robot_player_snapshot
SET direct_parent_wxid = 'wo356xnswd',
    direct_parent_no = '7584',
    parent_path = 'acnj6oxey9###wo356xnswd###',
    level_no = 3,
    balance = 1098.0000,
    total_flow = 2000.0000,
    used_flow = 1200.0000,
    remaining_flow = 800.0000,
    total_up = 500.0000,
    total_down = 200.0000,
    total_profit_loss = 50.0000,
    rebate_rate = 120.0000,
    total_rebate = 20.0000,
    source_updated_at = @ts,
    last_event_id = CONCAT(@group_id, '_test_seed_mfwfuwohuv_', @ts),
    last_sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'mfwfuwohuv';

-- 5. 更新 2026-07-13 历史归档
UPDATE robot_player_daily_summary
SET direct_parent_wxid = '',
    direct_parent_no = '',
    parent_path = '',
    level_no = 1,
    balance = 9800.0000,
    total_flow = 7500.0000,
    used_flow = 4500.0000,
    remaining_flow = 3000.0000,
    total_up = 4800.0000,
    total_down = 1800.0000,
    total_profit_loss = -400.0000,
    pending_rebate = 90.0000,
    total_rebate = 120.0000,
    rebate_rate = 300.0000,
    sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'acnj6oxey9'
  AND business_date = '2026-07-13';

UPDATE robot_player_daily_summary
SET direct_parent_wxid = 'acnj6oxey9',
    direct_parent_no = '7583',
    parent_path = 'acnj6oxey9###',
    level_no = 2,
    balance = 5200.0000,
    total_flow = 4800.0000,
    used_flow = 3600.0000,
    remaining_flow = 1200.0000,
    total_up = 1800.0000,
    total_down = 400.0000,
    total_profit_loss = 150.0000,
    pending_rebate = 21.6000,
    total_rebate = 55.0000,
    rebate_rate = 180.0000,
    sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'w4ajsj6p3a'
  AND business_date = '2026-07-13';

UPDATE robot_player_daily_summary
SET direct_parent_wxid = 'acnj6oxey9',
    direct_parent_no = '7583',
    parent_path = 'acnj6oxey9###',
    level_no = 2,
    balance = 7600.0000,
    total_flow = 5800.0000,
    used_flow = 3800.0000,
    remaining_flow = 2000.0000,
    total_up = 2800.0000,
    total_down = 900.0000,
    total_profit_loss = -250.0000,
    pending_rebate = 50.0000,
    total_rebate = 70.0000,
    rebate_rate = 250.0000,
    sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'wo356xnswd'
  AND business_date = '2026-07-13';

UPDATE robot_player_daily_summary
SET direct_parent_wxid = 'wo356xnswd',
    direct_parent_no = '7584',
    parent_path = 'acnj6oxey9###wo356xnswd###',
    level_no = 3,
    balance = 1000.0000,
    total_flow = 1800.0000,
    used_flow = 1000.0000,
    remaining_flow = 800.0000,
    total_up = 450.0000,
    total_down = 150.0000,
    total_profit_loss = 30.0000,
    pending_rebate = 9.6000,
    total_rebate = 15.0000,
    rebate_rate = 120.0000,
    sync_reason = 'test_seed'
WHERE player_group_id = @group_id
  AND wxid = 'mfwfuwohuv'
  AND business_date = '2026-07-13';

-- 6. 补充前一天历史（便于测 history 多日）
INSERT INTO robot_player_daily_summary (
    event_id, player_group_id, statistics_group_id, business_date, business_timestamp,
    wxid, player_no, nickname, display_name, player_type, balance,
    direct_parent_wxid, direct_parent_no, parent_path, level_no, active,
    total_flow, used_flow, remaining_flow, pending_rebate,
    total_up, total_down, total_profit_loss, rebate_rate, rebate_rate_unit,
    total_rebate, sync_reason
)
SELECT
    CONCAT(@group_id, '_test_seed_', s.wxid, '_20260712'),
    @group_id, @stats_id, '2026-07-12', @ts - 86400,
    s.wxid, s.player_no, s.nickname, s.display_name, s.player_type,
    s.balance * 0.9,
    s.direct_parent_wxid, s.direct_parent_no, s.parent_path, s.level_no, 1,
    s.total_flow * 0.8, s.used_flow * 0.7, s.remaining_flow * 0.9,
    s.remaining_flow * s.rebate_rate / 10000,
    s.total_up * 0.8, s.total_down * 0.7, s.total_profit_loss * 0.8,
    s.rebate_rate, s.rebate_rate_unit, s.total_rebate * 0.8, 'test_seed'
FROM robot_player_snapshot s
WHERE s.player_group_id = @group_id
  AND s.wxid IN ('acnj6oxey9', 'w4ajsj6p3a', 'wo356xnswd', 'mfwfuwohuv')
  AND NOT EXISTS (
      SELECT 1 FROM robot_player_daily_summary d
      WHERE d.player_group_id = @group_id
        AND d.wxid = s.wxid
        AND d.business_date = '2026-07-12'
  );
