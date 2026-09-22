package com.chat99.server.kefu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "kefu.proxy-enabled", havingValue = "true", matchIfMissing = true)
public class KefuWebSocketConfig implements WebSocketConfigurer {

    private final KefuCableProxyHandler kefuCableProxyHandler;

    public KefuWebSocketConfig(KefuCableProxyHandler kefuCableProxyHandler) {
        this.kefuCableProxyHandler = kefuCableProxyHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(kefuCableProxyHandler, "/kefu/cable")
            .setAllowedOriginPatterns("*");
    }
}
