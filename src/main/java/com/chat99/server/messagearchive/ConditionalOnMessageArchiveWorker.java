package com.chat99.server.messagearchive;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 归档写入侧：Kafka 消费、DLQ、回填、建表、对账、滞后监控。
 * 主服切流后设 {@code MSG_ARCHIVE_WORKER_ENABLED=false}，由独立节点接管，避免双消费/双 Job。
 * 主服仍可 {@code MSG_ARCHIVE_ENABLED=true} 保留 webhook 投递与 GET 历史/快照。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(name = "chat99.message-archive.worker-enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnMessageArchiveWorker {
}
