package com.chat99.sangong.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * 抑制 Kafka client 在 metadata bootstrap 阶段的两条高频 WARN：
 *   - "Connection to node -1 ... could not be established"
 *   - "Bootstrap broker ... disconnected"
 * 这两条日志只在客户端还不知道 broker 地址时出现，重连成功后即恢复正常。
 * 抑制后不影响消费恢复（Discovered group coordinator / Successfully joined 仍为 INFO 输出）。
 *
 * 用法（logback-spring.xml）：
 *   <turboFilter class="com.chat99.sangong.common.logging.KafkaNoiseFilter"/>
 */
public class KafkaNoiseFilter extends TurboFilter {

    private static final String NETWORK_CLIENT = "org.apache.kafka.clients.NetworkClient";
    private static final String NODE_MINUS_ONE = "Connection to node -1";
    private static final String BOOTSTRAP_BROKER = "Bootstrap broker";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (logger == null) {
            return FilterReply.NEUTRAL;
        }
        if (!NETWORK_CLIENT.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }
        String msg = format;
        if (msg == null && params != null && params.length > 0 && params[0] != null) {
            msg = String.valueOf(params[0]);
        }
        if (msg != null && (msg.contains(NODE_MINUS_ONE) || msg.contains(BOOTSTRAP_BROKER))) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}