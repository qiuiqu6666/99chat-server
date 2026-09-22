-- 充值入账后即时归集使用 trigger_type=DEPOSIT；旧表 ENUM 未包含该值会导致写日志失败、TG 归集通知发不出。
ALTER TABLE wallet_sweep_log
    MODIFY COLUMN trigger_type ENUM('AUTO','COLLECT_ALL','MANUAL','DEPOSIT') NOT NULL;
