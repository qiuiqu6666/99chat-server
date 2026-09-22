-- Per-user sticker pack items (decouple upload pack from sticker ownership).
-- Migrate existing user_upload entries from sticker_pack_item.

CREATE TABLE IF NOT EXISTS user_sticker_pack_item (
    user_id    VARCHAR(10)  NOT NULL,
    pack_id    VARCHAR(64)  NOT NULL,
    sticker_id VARCHAR(64)  NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, pack_id, sticker_id),
    INDEX idx_user_pack_sort (user_id, pack_id, sort_order)
);

INSERT INTO user_sticker_pack_item (user_id, pack_id, sticker_id, sort_order)
SELECT s.owner_user_id, i.pack_id, i.sticker_id, i.sort_order
FROM sticker_pack_item i
JOIN sticker s ON s.sticker_id = i.sticker_id
WHERE i.pack_id = 'user_upload'
  AND s.owner_user_id IS NOT NULL
ON DUPLICATE KEY UPDATE sort_order = VALUES(sort_order);

-- Widen sticker_id columns for stk_ prefix IDs.
ALTER TABLE sticker MODIFY COLUMN sticker_id VARCHAR(64) NOT NULL;
ALTER TABLE sticker_pack_item MODIFY COLUMN sticker_id VARCHAR(64) NOT NULL;
ALTER TABLE user_sticker_favorite MODIFY COLUMN sticker_id VARCHAR(64) NOT NULL;
