CREATE TABLE IF NOT EXISTS sangong_agent_chat_bindings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(128) NOT NULL,
    agent_im_user_id VARCHAR(64) NOT NULL,
    agent_im_group_id VARCHAR(128) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_chat_tenant_user (tenant_id, agent_im_user_id),
    UNIQUE KEY uk_agent_chat_user_group (agent_im_user_id, agent_im_group_id),
    KEY idx_agent_chat_group (agent_im_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
