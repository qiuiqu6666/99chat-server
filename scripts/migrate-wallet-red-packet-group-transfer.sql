-- 群转账：packet_type 增加 GROUP_TRANSFER（原 ENUM 无此值会导致 Data truncated）
ALTER TABLE wallet_red_packet
  MODIFY COLUMN packet_type ENUM(
    'EXCLUSIVE',
    'LUCKY_GROUP',
    'NORMAL_C2C',
    'NORMAL_GROUP',
    'GROUP_TRANSFER'
  ) NOT NULL;
