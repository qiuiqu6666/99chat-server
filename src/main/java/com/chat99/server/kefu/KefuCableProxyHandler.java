package com.chat99.server.kefu;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 把浏览器 {@code /kefu/cable} 桥接到 Chatwoot ActionCable {@code /cable}。
 */
@Component
@ConditionalOnProperty(name = "kefu.proxy-enabled", havingValue = "true", matchIfMissing = true)
public class KefuCableProxyHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(KefuCableProxyHandler.class);
    private static final String UPSTREAM = "kefu.cable.upstream";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final URI upstreamCable;
    private final String origin;
    private final List<String> rewriteOrigins;
    private final String configuredPublicBase;

    public KefuCableProxyHandler(
            @Value("${kefu.service-url:http://127.0.0.1:3000}") String kefuServiceUrl,
            @Value("${kefu.upstream-public-url:http://43.154.162.29}") String upstreamPublicUrl,
            @Value("${kefu.public-base-url:}") String configuredPublicBase) {
        String http = KefuUrlRewriter.stripSlash(kefuServiceUrl);
        String ws = http.startsWith("https://")
            ? "wss://" + http.substring("https://".length())
            : "ws://" + (http.startsWith("http://") ? http.substring("http://".length()) : http);
        this.upstreamCable = URI.create(ws + "/cable");
        this.origin = KefuUrlRewriter.stripSlash(upstreamPublicUrl);
        this.rewriteOrigins = KefuUrlRewriter.upstreamOrigins(http, this.origin);
        this.configuredPublicBase = KefuUrlRewriter.stripSlash(configuredPublicBase);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String publicBase = configuredPublicBase.isEmpty()
            ? inferPublicBase(session)
            : configuredPublicBase;
        UpstreamListener listener = new UpstreamListener(session, rewriteOrigins, publicBase);
        var builder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (!origin.isEmpty()) {
            builder.header("Origin", origin);
        }
        try {
            WebSocket upstream = builder.buildAsync(upstreamCable, listener)
                .get(10, TimeUnit.SECONDS);
            session.getAttributes().put(UPSTREAM, upstream);
        } catch (Exception e) {
            log.error("kefu cable upstream connect failed target={} reason={}",
                upstreamCable, e.toString());
            session.close(CloseStatus.SERVER_ERROR.withReason("kefu cable unavailable"));
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
        log.debug("kefu cable client transport error session={} reason={}",
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

    private static String inferPublicBase(WebSocketSession session) {
        String proto = firstHop(session.getHandshakeHeaders().getFirst("X-Forwarded-Proto"));
        String host = firstHop(session.getHandshakeHeaders().getFirst("X-Forwarded-Host"));
        if (host == null) {
            host = firstHop(session.getHandshakeHeaders().getFirst("Host"));
        }
        URI uri = session.getUri();
        if (proto == null) {
            proto = (uri != null && ("https".equalsIgnoreCase(uri.getScheme())
                || "wss".equalsIgnoreCase(uri.getScheme()))) ? "https" : "http";
        }
        if (host == null && uri != null && uri.getHost() != null) {
            int port = uri.getPort();
            boolean defaultPort = port < 0
                || ("http".equals(proto) && port == 80)
                || ("https".equals(proto) && port == 443);
            host = defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
        }
        if (host == null) {
            return "";
        }
        return proto + "://" + host + "/kefu";
    }

    private static String firstHop(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
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
        private final List<String> rewriteOrigins;
        private final String publicBase;
        private final StringBuilder partialText = new StringBuilder();

        private UpstreamListener(WebSocketSession session, List<String> rewriteOrigins, String publicBase) {
            this.session = session;
            this.rewriteOrigins = rewriteOrigins;
            this.publicBase = publicBase;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                String text = KefuUrlRewriter.rewrite(partialText.toString(), publicBase, rewriteOrigins);
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
            log.debug("kefu cable upstream error session={} reason={}", session.getId(), error.toString());
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
