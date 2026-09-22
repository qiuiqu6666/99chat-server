-- 生产上线前清理测试数据
-- 保留用户：99Chat、99Messenger
-- 保留：admin_account、app_setting、钱包/闪兑/币种配置、贴纸系统包、official_accounts、app_client_version

SET NAMES utf8mb4;

START TRANSACTION;

-- ========== 钱包明细（先子表后主表）==========
DELETE FROM wallet_red_packet_claim
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR packet_id IN (
        SELECT id FROM (
            SELECT id FROM wallet_red_packet
             WHERE sender_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
                OR (exclusive_user_id IS NOT NULL
                    AND exclusive_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger'))
        ) t
    );

DELETE FROM wallet_red_packet
 WHERE sender_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR (exclusive_user_id IS NOT NULL
        AND exclusive_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger'));

DELETE FROM wallet_ledger
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM wallet_deposit
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM wallet_withdrawal
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM wallet_exchange_order
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM wallet_transfer
 WHERE from_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR to_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM wallet_sweep_log
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_wallet
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

-- ========== 社交 / 行为 ==========
DELETE FROM friend_application_history
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR peer_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM user_starred_friend
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR friend_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM user_friend
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR friend_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM user_message_favorite
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_feedback
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_sticker_favorite
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_sticker_pack
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM sticker
 WHERE owner_user_id IS NOT NULL
   AND owner_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM user_conversation_notify
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_push_token
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

-- ========== 公告 ==========
DELETE FROM announcement_read
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM announcement;

-- ========== 通话 ==========
DELETE FROM call_record_user
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR peer_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM call_session
 WHERE caller_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger')
    OR callee_user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

DELETE FROM call_callback_log;

-- ========== 云端同步 ==========
DELETE FROM user_photo_upload
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_photo
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_contact_item
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM sync_session
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');
DELETE FROM user_sync_state
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

-- ========== 登录 / 设备 ==========
DELETE FROM login_log;
DELETE FROM user_device
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

-- ========== 群组本地配置（群本体在腾讯 IM）==========
DELETE FROM group_settings;

-- ========== 管理后台测试审计（保留 admin 账号）==========
DELETE FROM admin_audit_logs;
DELETE FROM admin_login_logs;
DELETE FROM admin_banned_device;
DELETE FROM admin_dashboard_daily;

-- ========== 平台统计归零 ==========
UPDATE wallet_platform_stats
   SET total_exchange_surplus_fen = 0,
       total_fee_usdt_micro = 0,
       total_fee_platform_fen = 0
 WHERE id = 1;

-- ========== 用户主表（最后删）==========
DELETE FROM users
 WHERE user_id COLLATE utf8mb4_unicode_ci NOT IN ('99Chat', '99Messenger');

COMMIT;

-- 验证
SELECT 'users_remaining' AS check_name, COUNT(*) AS cnt FROM users;
SELECT user_id, nickname FROM users ORDER BY user_id;
