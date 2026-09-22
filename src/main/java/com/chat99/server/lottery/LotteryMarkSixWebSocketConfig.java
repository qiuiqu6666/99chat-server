package com.chat99.server.lottery;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class LotteryMarkSixWebSocketConfig implements WebSocketConfigurer {

    private final LotteryMarkSixWebSocketProxyHandler handler;

    public LotteryMarkSixWebSocketConfig(LotteryMarkSixWebSocketProxyHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/lotteries/mark-six-demo/ws")
            .setAllowedOriginPatterns("*");
    }
}
