package com.chat99.server.realtime;

import com.chat99.server.user.PresenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RealtimeTcpHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTcpHandler.class);

    private final RealtimeAuthService authService;
    private final RealtimeSessionRegistry sessions;
    private final RealtimeProperties props;
    private final ObjectMapper json;
    private final PresenceService presenceService;
    private final Executor queryExecutor;

    private String userId;
    private String deviceId;
    private ScheduledFuture<?> authTimeoutTask;
    private final AtomicInteger presenceQueryInflight = new AtomicInteger();

    RealtimeTcpHandler(RealtimeAuthService authService,
                       RealtimeSessionRegistry sessions,
                       RealtimeProperties props,
                       ObjectMapper json,
                       PresenceService presenceService,
                       Executor queryExecutor) {
        this.authService = authService;
        this.sessions = sessions;
        this.props = props;
        this.json = json;
        this.presenceService = presenceService;
        this.queryExecutor = queryExecutor;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        authTimeoutTask = ctx.executor().schedule(
            () -> closeIfUnauthenticated(ctx, "auth_timeout"),
            props.authTimeoutSeconds(),
            TimeUnit.SECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        Map<String, Object> body = json.readValue(msg, new TypeReference<>() {});
        String type = stringVal(body.get("type"));
        if (type == null) {
            writeLine(ctx, Map.of("type", "error", "code", "INVALID_INPUT"));
            return;
        }
        if (userId == null) {
            if ("auth".equals(type)) {
                handleAuth(ctx, body);
            } else {
                writeLine(ctx, Map.of("type", "auth_fail", "code", "UNAUTHORIZED"));
                ctx.close();
            }
            return;
        }
        switch (type) {
            case "ping" -> handlePing(ctx, body);
            case "presence_last_seen" -> handlePresenceLastSeen(ctx, body);
            default -> writeLine(ctx, Map.of("type", "error", "code", "UNKNOWN_TYPE"));
        }
    }

    private void handlePing(ChannelHandlerContext ctx, Map<String, Object> body) {
        String pingDeviceId = stringVal(body.get("deviceId"));
        if (pingDeviceId != null) {
            deviceId = pingDeviceId;
        }
        touchPresence();
        Map<String, Object> pong = new LinkedHashMap<>();
        pong.put("type", "pong");
        pong.put("ts", System.currentTimeMillis());
        writeLine(ctx, pong);
    }

    private void handlePresenceLastSeen(ChannelHandlerContext ctx, Map<String, Object> body) {
        Object parsed = RealtimePresenceQuerySupport.parse(body, presenceService.maxBatchSize());
        if (parsed instanceof RealtimePresenceQuerySupport.ParseError err) {
            String reqId = stringVal(body.get("requestId"));
            writeLine(ctx, RealtimePresenceQuerySupport.failPayload(reqId, err.code()));
            return;
        }
        RealtimePresenceQuerySupport.ParsedQuery query =
            (RealtimePresenceQuerySupport.ParsedQuery) parsed;
        if (presenceQueryInflight.get() >= RealtimePresenceQuerySupport.MAX_INFLIGHT) {
            writeLine(ctx, RealtimePresenceQuerySupport.failPayload(query.requestId(), "TOO_MANY_INFLIGHT"));
            return;
        }
        presenceQueryInflight.incrementAndGet();
        String viewerUserId = userId;
        queryExecutor.execute(() -> {
            try {
                PresenceService.LastSeenSnapshot snapshot =
                    presenceService.lastSeen(viewerUserId, query.userIds());
                Map<String, Object> ok = RealtimePresenceQuerySupport.okPayload(query.requestId(), snapshot);
                ctx.channel().eventLoop().execute(() -> {
                    try {
                        if (ctx.channel().isActive()) {
                            writeLine(ctx, ok);
                        }
                    } finally {
                        presenceQueryInflight.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                log.warn("realtime presence_last_seen failed userId={} err={}", viewerUserId, e.getMessage());
                Map<String, Object> fail =
                    RealtimePresenceQuerySupport.failPayload(query.requestId(), "INTERNAL");
                ctx.channel().eventLoop().execute(() -> {
                    try {
                        if (ctx.channel().isActive()) {
                            writeLine(ctx, fail);
                        }
                    } finally {
                        presenceQueryInflight.decrementAndGet();
                    }
                });
            }
        });
    }

    private void touchPresence() {
        if (userId == null) {
            return;
        }
        if (deviceId != null) {
            presenceService.deviceHeartbeat(userId, deviceId);
        } else {
            presenceService.heartbeat(userId);
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, Map<String, Object> body) {
        String token = stringVal(body.get("token"));
        String deviceId = stringVal(body.get("deviceId"));
        var auth = authService.authenticate(token, deviceId);
        if (auth.isEmpty()) {
            writeLine(ctx, Map.of("type", "auth_fail", "code", "UNAUTHORIZED"));
            ctx.close();
            return;
        }
        userId = auth.get().userId();
        this.deviceId = stringVal(body.get("deviceId"));
        cancelAuthTimeout();
        sessions.register(userId, ctx.channel());
        touchPresence();
        writeLine(ctx, Map.of("type", "auth_ok"));
        log.info("realtime tcp authenticated userId={} channel={}", userId, ctx.channel().id());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.debug("realtime tcp idle close userId={} channel={}", userId, ctx.channel().id());
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelAuthTimeout();
        sessions.unregister(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("realtime tcp error userId={} err={}", userId, cause.getMessage());
        ctx.close();
    }

    private void closeIfUnauthenticated(ChannelHandlerContext ctx, String reason) {
        if (userId == null && ctx.channel().isActive()) {
            log.debug("realtime tcp closing unauthenticated channel={} reason={}", ctx.channel().id(), reason);
            ctx.close();
        }
    }

    private void cancelAuthTimeout() {
        if (authTimeoutTask != null) {
            authTimeoutTask.cancel(false);
            authTimeoutTask = null;
        }
    }

    private void writeLine(ChannelHandlerContext ctx, Map<String, Object> payload) {
        try {
            ctx.writeAndFlush(json.writeValueAsString(payload) + "\n");
        } catch (Exception e) {
            log.warn("realtime tcp write failed: {}", e.getMessage());
        }
    }

    private static String stringVal(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
