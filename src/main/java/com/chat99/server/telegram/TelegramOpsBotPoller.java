package com.chat99.server.telegram;

import com.chat99.server.telegram.TelegramBotClient.TelegramMessage;
import com.chat99.server.telegram.TelegramBotClient.TelegramUpdate;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 长轮询运维群消息：
 * <ul>
 *   <li>钱包查询群：{@code @uid} / 手机号查余额</li>
 *   <li>游戏控制群：开群特权 {@code 配对机器码} / {@code 开启@群ID}</li>
 * </ul>
 */
@Component
public class TelegramOpsBotPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramOpsBotPoller.class);

    private final TelegramOpsProperties props;
    private final TelegramBotClient botClient;
    private final TelegramWalletQueryService queryService;
    private final TelegramGroupGameControlService gameControlService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollThread;

    public TelegramOpsBotPoller(TelegramOpsProperties props,
                                TelegramBotClient botClient,
                                TelegramWalletQueryService queryService,
                                TelegramGroupGameControlService gameControlService) {
        this.props = props;
        this.botClient = botClient;
        this.queryService = queryService;
        this.gameControlService = gameControlService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.isPollerReady()) {
            log.info("telegram ops bot poller disabled");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        pollThread = new Thread(this::pollLoop, "telegram-ops-bot-poller");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("telegram ops bot poller started opsChatId={} gameControlChatId={}",
            props.chatId(), props.gameControlChatId());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }

    private void pollLoop() {
        long offset = drainPendingUpdates();
        int timeout = (int) Math.min(props.pollTimeoutSeconds(), 50);
        int conflictCount = 0;
        while (running.get()) {
            try {
                List<TelegramUpdate> updates = botClient.getUpdates(offset, timeout);
                conflictCount = 0;
                for (TelegramUpdate update : updates) {
                    offset = update.updateId() + 1;
                    handle(update.message());
                }
            } catch (Throwable e) {
                // NoClassDefFoundError 等 Error 也必须吞住并续跑，否则线程直接死掉且群内再无回复
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (isConflict(e)) {
                    conflictCount++;
                    long delay = Math.min(60_000L, 5_000L * conflictCount);
                    log.warn("telegram poll conflict count={} retryInMs={} err={}", conflictCount, delay, e.toString());
                    sleepQuiet(delay + ThreadLocalRandom.current().nextLong(1_000L));
                } else {
                    log.warn("telegram poll error: {}", e.toString());
                    sleepQuiet(2_000);
                }
            }
        }
        log.warn("telegram ops bot poller stopped");
    }

    private static boolean isConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("status=409")) {
                return true;
            }
        }
        return false;
    }

    /** 启动时丢弃积压更新，避免重启后重复回复历史消息。 */
    private long drainPendingUpdates() {
        long offset = 0L;
        int drained = 0;
        try {
            while (true) {
                List<TelegramUpdate> batch = botClient.getUpdates(offset, 0);
                if (batch.isEmpty()) {
                    break;
                }
                drained += batch.size();
                offset = batch.get(batch.size() - 1).updateId() + 1;
            }
        } catch (Throwable e) {
            log.warn("telegram drain pending updates failed: {}", e.toString());
        }
        if (drained > 0) {
            log.info("telegram drain skipped {} pending updates offset={}", drained, offset);
        }
        return offset;
    }

    private void handle(TelegramMessage message) {
        if (message == null) {
            return;
        }
        if (message.fromBot()) {
            return;
        }
        String chatId = message.chatId();
        String text = message.text();
        boolean inGameChat = TelegramGroupGameControlService.chatIdMatches(chatId, props.gameControlChatId());
        boolean inOpsChat = chatId != null && props.chatId() != null
            && TelegramGroupGameControlService.chatIdMatches(chatId, props.chatId());
        if (inGameChat || inOpsChat) {
            log.info("telegram inbound chatId={} inGame={} inOps={} text={}",
                chatId, inGameChat, inOpsChat, preview(text));
        }

        if (gameControlService.shouldHandleMessage(chatId, text, false)) {
            try {
                String reply = gameControlService.handle(chatId, text);
                if (reply != null && !reply.isBlank()) {
                    botClient.sendHtmlToChat(chatId, reply);
                }
            } catch (Exception e) {
                log.warn("telegram group game control failed: {}", e.getMessage());
                botClient.sendHtmlToChat(chatId,
                    "⚠️ 开群特权失败: " + TelegramGroupGameControlService.esc(e.getMessage()));
            }
            return;
        }

        // 开群指令发到了非控制群（常见：发到钱包通知群）→ 明确提示，避免静默
        if (text != null && !TelegramGroupGameControlService.extractCommandSegments(text).isEmpty()
            && !inGameChat && props.isGameControlReady()) {
            log.info("telegram game-control command in wrong chat chatId={} text={}", chatId, preview(text));
            botClient.sendHtmlToChat(chatId,
                "ℹ️ 开群特权请发到控制群（当前配置 chatId=<code>"
                    + TelegramGroupGameControlService.esc(props.gameControlChatId())
                    + "</code>），群名一般含「设置反水」字样。\n"
                    + "推荐：\n<code>配对xxxx-xxxx-xxxx</code>\n<code>开启@TGS#完整群ID</code>");
            return;
        }

        if (!queryService.shouldHandleMessage(chatId, text, false)) {
            if (inGameChat && text != null && !text.isBlank()) {
                log.info("telegram game-control chat ignored (not a command) text={}", preview(text));
                if (text.contains("配对") || text.contains("开启") || text.contains("关闭") || text.contains("停用")) {
                    botClient.sendHtmlToChat(chatId,
                        "❓ 未识别开群指令。可一条发完或分两条：\n"
                            + "<code>配对xxxx-xxxx-xxxx</code>\n"
                            + "<code>开启@TGS#完整群ID</code>\n"
                            + "例如: <code>配对CP5Y-4EX1-AC9V 开启@TGS#27PIAKM5CC</code>\n"
                            + "查状态直接发完整群ID，例如: <code>@TGS#27PIAKM5CC</code>");
                }
            }
            return;
        }
        try {
            String reply = queryService.buildReply(text);
            if (reply != null && !reply.isBlank()) {
                botClient.sendHtmlToChat(chatId, reply);
            }
        } catch (Exception e) {
            log.warn("telegram wallet query failed: {}", e.getMessage());
            botClient.sendHtmlToChat(chatId, "⚠️ 查询失败: " + TelegramWalletQueryService.esc(e.getMessage()));
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "<null>";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "...";
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
