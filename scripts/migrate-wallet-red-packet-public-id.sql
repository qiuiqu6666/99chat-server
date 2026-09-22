-- 红包对外字符串 ID（IM 卡片 / 客户端路由，格式 red_packet_{uuid}）
ALTER TABLE wallet_red_packet
    ADD COLUMN IF NOT EXISTS public_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_red_packet_public_id
    ON wallet_red_packet (public_id);
