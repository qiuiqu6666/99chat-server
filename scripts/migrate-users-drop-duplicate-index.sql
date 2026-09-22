-- users 表存在两条完全相同的唯一索引：uk_user_id(user_id) 与 user_id(user_id)。
-- 保留实体声明的 uk_user_id，删除冗余的 user_id，减少每次写入的索引维护开销。
-- 已核对：无外键引用 users.user_id；JPA ddl-auto=none 不会重建。

ALTER TABLE users DROP INDEX user_id;
