-- Align sticker user_id columns with users.user_id (varchar(32)).
ALTER TABLE sticker MODIFY COLUMN owner_user_id VARCHAR(32) NULL;
ALTER TABLE user_sticker_favorite MODIFY COLUMN user_id VARCHAR(32) NOT NULL;
ALTER TABLE user_sticker_pack MODIFY COLUMN user_id VARCHAR(32) NOT NULL;
ALTER TABLE user_sticker_pack_item MODIFY COLUMN user_id VARCHAR(32) NOT NULL;
