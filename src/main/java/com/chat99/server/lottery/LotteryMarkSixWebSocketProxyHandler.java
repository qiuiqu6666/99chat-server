package com.chat99.server.lottery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 把 {@code /api/v1/lotteries/mark-six-demo/ws} 桥接到同一路径的上游 WebSocket。
 * 查询参数（含 machineCode、window、limit）原样带上。
 */
@Component
public class LotteryMarkSixWebSocketProxyHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LotteryMarkSixWebSocketProxyHandler.class);
    private static final String UPSTREAM = "lottery.ws.upstream";
    private static final String PATH = "/api/v1/lotteries/mark-six-demo/ws";
    private static final Set<String> SKIP_HEADERS = Set.of(
        "host", "connection", "upgrade", "content-length", "transfer-encoding",
        "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer", "expect",
        "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions",
        "sec-websocket-accept", "sec-websocket-protocol");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final String upstreamBase;

    public LotteryMarkSixWebSocketProxyHandler(
            @Value("${robot.service-url:http://127.0.0.1:8091}") String serviceUrl) {
        String http = serviceUrl.endsWith("/")
            ? serviceUrl.substring(0, serviceUrl.length() - 1)
            : serviceUrl;
        if (http.startsWith("https://")) {
            this.upstreamBase = "wss://" + http.substring("https://".length());
        } else if (http.startsWith("http://")) {
            this.upstreamBase = "ws://" + http.substring("http://".length());
        } else {
            this.upstreamBase = "ws://" + http;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI clientUri = session.getUri();
        String query = clientUri == null ? null : clientUri.getRawQuery();
        URI target = URI.create(upstreamBase + PATH + (query == null || query.isBlank() ? "" : "?" + query));
        var builder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        copyHeaders(session.getHandshakeHeaders(), builder);
        UpstreamListener listener = new UpstreamListener(session);
        try {
            WebSocket upstream = builder.buildAsync(target, listener).get(10, TimeUnit.SECONDS);
            session.getAttributes().put(UPSTREAM, upstream);
        } catch (Exception e) {
            log.error("lottery ws upstream connect failed target={} reason={}", target, e.toString());
            session.close(CloseStatus.SERVER_ERROR.withReason("lottery service unavailable"));
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        WebSocket upstream = (WebSocket) session.getAttributes().get(UPSTREAM);
        if (upstream == null) {
            return;
        }
        if (message instanceof TextMessage text) {
            upstream.sendText(text.getPayload(), text.isLast());
        } else if (message instanceof BinaryMessage binary) {
            upstream.sendBinary(binary.getPayload(), binary.isLast());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("lottery ws client transport error session={} reason={}",
            session.getId(), exception.toString());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        WebSocket upstream = (WebSocket) session.getAttributes().remove(UPSTREAM);
        if (upstream != null) {
            try {
                upstream.sendClose(WebSocket.NORMAL_CLOSURE, closeStatus.getReason() == null
                    ? "" : closeStatus.getReason());
            } catch (Exception ignored) {
                upstream.abort();
            }
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    private static void copyHeaders(HttpHeaders headers, java.net.http.WebSocket.Builder builder) {
        if (headers == null) {
            return;
        }
        for (String name : headers.keySet()) {
            if (SKIP_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            List<String> values = headers.get(name);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // JDK HttpClient 拒绝的受限头，跳过
                }
            }
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static final class UpstreamListener implements WebSocket.Listener {
        private final WebSocketSession session;
        private final StringBuilder partialText = new StringBuilder();

        private UpstreamListener(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                String text = partialText.toString();
                partialText.setLength(0);
                sendToClient(new TextMessage(text));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            sendToClient(new BinaryMessage(data, last));
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            CloseStatus status = CloseStatus.NORMAL;
            try {
                if (statusCode > 0) {
                    status = new CloseStatus(statusCode, reason == null ? "" : reason);
                }
            } catch (IllegalArgumentException ignored) {
                status = CloseStatus.NORMAL;
            }
            closeQuietly(session, status);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("lottery ws upstream error session={} reason={}", session.getId(), error.toString());
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }

        private void sendToClient(WebSocketMessage<?> message) {
            if (!session.isOpen()) {
                return;
            }
            synchronized (session) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                } catch (IOException ignored) {
                    closeQuietly(session, CloseStatus.SERVER_ERROR);
                }
            }
        }
    }
}
