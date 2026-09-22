package com.chat99.server.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度总开关：默认开启，与原 {@code @EnableScheduling} 行为一致。
 * 仅在旁路验证 / 模拟启动时通过 {@code CHAT99_SCHEDULING_ENABLED=false} 关闭，
 * 避免与现网实例双跑 {@code @Scheduled} 任务。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "chat99.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
