package com.chat99.server.realtime;

import com.chat99.server.user.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RealtimeTcpServer {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTcpServer.class);

    private final RealtimeProperties props;
    private final RealtimeAuthService authService;
    private final RealtimeSessionRegistry sessions;
    private final RealtimeConnectionLimiter connectionLimiter;
    private final ObjectMapper json;
    private final PresenceService presenceService;
    private final Executor queryExecutor;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RealtimeTcpServer(RealtimeProperties props, RealtimeAuthService authService,
                             RealtimeSessionRegistry sessions, RealtimeConnectionLimiter connectionLimiter,
                             ObjectMapper json, PresenceService presenceService,
                             @Qualifier(RealtimeQueryExecutorConfig.BEAN_NAME) Executor queryExecutor) {
        this.props = props;
        this.authService = authService;
        this.sessions = sessions;
        this.connectionLimiter = connectionLimiter;
        this.json = json;
        this.presenceService = presenceService;
        this.queryExecutor = queryExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.enabled()) {
            log.info("realtime tcp disabled");
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (!connectionLimiter.tryAcquire(ch)) {
                            ch.close();
                            return;
                        }
                        ch.pipeline()
                            .addLast(new LineBasedFrameDecoder(props.maxFrameLength()))
                            .addLast(new StringDecoder(StandardCharsets.UTF_8))
                            .addLast(new StringEncoder(StandardCharsets.UTF_8))
                            .addLast(new IdleStateHandler(props.idleTimeoutSeconds(), 0, 0, TimeUnit.SECONDS))
                            .addLast(new RealtimeTcpHandler(
                                authService, sessions, props, json, presenceService, queryExecutor));
                    }
                });
            serverChannel = bootstrap.bind(props.tcpPort()).sync().channel();
            log.info("realtime tcp listening port={}", props.tcpPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("realtime tcp start interrupted", e);
        } catch (Exception e) {
            shutdownGroups();
            throw new IllegalStateException("realtime tcp start failed", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        shutdownGroups();
    }

    private void shutdownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }
}
