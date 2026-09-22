package com.chat99.server.notify;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.wallet.ExchangeDirection;
import com.chat99.server.wallet.WalletExchangeOrder;
import com.chat99.server.wallet.WalletRedPacket;
import com.chat99.server.wallet.WalletWithdrawal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlatformWalletNoticeService {

    public static final String CUSTOM_TYPE = "platform_wallet_notice";

    private static final Logger log = LoggerFactory.getLogger(PlatformWalletNoticeService.class);
    private static final int FRIEND_ADD_ATTEMPTS = 3;
    private static final long FRIEND_ADD_RETRY_MS = 500L;
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final PlatformWalletNoticeProperties props;
    private final ImAdminClient imAdmin;
    private final PushService pushService;
    private final UserFriendService userFriendService;
    private final ImUserIdService imUserIdService;

    public PlatformWalletNoticeService(PlatformWalletNoticeProperties props,
                                       ImAdminClient imAdmin,
                                       PushService pushService,
                                       UserFriendService userFriendService,
                                       ImUserIdService imUserIdService) {
        this.props = props;
        this.imAdmin = imAdmin;
        this.pushService = pushService;
        this.userFriendService = userFriendService;
        this.imUserIdService = imUserIdService;
    }

    public String senderUserId() {
        return props.senderUserId();
    }

    /** 注册后自动与支付助手建立双向好友（无欢迎消息）。 */
    public void onUserRegistered(String userId) {
        if (props.autoFriendOnRegister()) {
            ensureFriend(userId);
        }
    }

    public void ensureFriend(String userId) {
        if (!props.autoFriendOnRegister()) {
            return;
        }
        addFriendWithRetry(userId);
    }

    public void send(PlatformWalletNoticeRequest req) {
        if (!props.notifyEnabled() || req == null) {
            return;
        }
        if (req.toUserId() == null || req.toUserId().isBlank()) {
            return;
        }
        if (req.title() == null || req.title().isBlank()) {
            log.warn("platform wallet notice skipped: empty title userId={}", req.toUserId());
            return;
        }
        ensureFriend(req.toUserId());
        Map<String, Object> data = toImData(req);
        try {
            imAdmin.sendCustomC2c(props.senderUserId(), imUserIdService.toIm(req.toUserId()), data, req.title());
            log.info("platform wallet notice sent type={} userId={} orderId={}",
                req.noticeType(), req.toUserId(), req.orderId());
        } catch (ImRestException e) {
            log.warn("platform wallet notice im failed userId={} code={} msg={}",
                req.toUserId(), e.imErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("platform wallet notice failed userId={} err={}", req.toUserId(), e.getMessage());
        }
        sendOfflinePush(req);
    }

    private void sendOfflinePush(PlatformWalletNoticeRequest req) {
        String body = req.summary() != null && !req.summary().isBlank() ? req.summary() : req.title();
        PushMessage message = PushMessage.of(req.title(), body)
            .withData("type", CUSTOM_TYPE)
            .withData("noticeType", req.noticeType())
            .withData("orderId", req.orderId());
        pushService.sendToUser(req.toUserId(), message);
    }

    public void notifyDeposit(String userId, long amountMicro, String txId) {
        String amount = formatUsdt(amountMicro);
        String orderId = "DEP" + shortTx(txId);
        send(new PlatformWalletNoticeRequest(
            userId, "deposit", "充币到账", props.serviceName(), "已到账",
            "您的链上充值已确认并入账。",
            List.of(
                row("充币数量", amount + " USDT", true),
                row("网络", "TRC20", false),
                row("交易哈希", shortTx(txId), false),
                row("时间", nowStr(), false)),
            "查看详情", null, orderId));
    }

    public void notifyWithdrawApproved(WalletWithdrawal w) {
        String amount = formatUsdt(w.getAmountMicro());
        String orderId = "WD" + w.getId();
        send(new PlatformWalletNoticeRequest(
            w.getUserId(), "withdraw", "提币审核通过", props.serviceName(), "处理中",
            "您的提币申请已审核通过，正在处理打款。",
            List.of(
                row("提币数量", amount + " USDT", true),
                row("到账地址", shortAddress(w.getToAddress()), false),
                row("手续费", formatUsdt(w.getFeeMicro()) + " USDT", false),
                row("订单号", orderId, false),
                row("时间", nowStr(), false)),
            "查看详情", null, orderId));
    }

    public void notifyWithdrawCompleted(WalletWithdrawal w, String chainTxId) {
        String amount = formatUsdt(w.getAmountMicro());
        String orderId = "WD" + w.getId();
        send(new PlatformWalletNoticeRequest(
            w.getUserId(), "withdraw", "提币成功", props.serviceName(), "成功",
            "您的提币申请已处理完成，资产已从平台钱包转出。",
            List.of(
                row("提币数量", amount + " USDT", true),
                row("到账地址", shortAddress(w.getToAddress()), false),
                row("手续费", formatUsdt(w.getFeeMicro()) + " USDT", false),
                row("订单号", orderId, false),
                row("链上交易", shortTx(chainTxId), false),
                row("时间", nowStr(), false)),
            "查看详情", null, orderId));
    }

    public void notifyWithdrawFailed(WalletWithdrawal w, String reason) {
        String amount = formatUsdt(w.getAmountMicro());
        String orderId = "WD" + w.getId();
        send(new PlatformWalletNoticeRequest(
            w.getUserId(), "withdraw", "提币失败", props.serviceName(), "失败",
            reason != null && !reason.isBlank() ? reason : "提币处理失败，金额已退回钱包。",
            List.of(
                row("提币数量", amount + " USDT", true),
                row("到账地址", shortAddress(w.getToAddress()), false),
                row("订单号", orderId, false),
                row("时间", nowStr(), false)),
            "查看详情", null, orderId));
    }

    public void notifyFlashExchange(WalletExchangeOrder order) {
        String orderId = "EX" + order.getId();
        List<PlatformWalletNoticeRow> rows = new ArrayList<>();
        if (order.getDirection() == ExchangeDirection.USDT_TO_PLATFORM) {
            rows.add(row("支付", formatUsdt(order.getInputAmount()) + " USDT", true));
            rows.add(row("获得", formatPlatform(order.getOutputAmount()) + " 99", true));
        } else {
            rows.add(row("支付", formatPlatform(order.getInputAmount()) + " 99", true));
            rows.add(row("获得", formatUsdt(order.getOutputAmount()) + " USDT", true));
        }
        rows.add(row("订单号", orderId, false));
        rows.add(row("时间", nowStr(), false));
        send(new PlatformWalletNoticeRequest(
            order.getUserId(), "flashExchange", "闪兑成功", props.serviceName(), "成功",
            "闪兑已完成，资产已计入您的钱包。",
            rows, "查看详情", null, orderId));
    }

    public void notifySetTradePassword(String userId) {
        send(new PlatformWalletNoticeRequest(
            userId, "setTradePassword", "资金密码设置成功", props.serviceName(), "成功",
            null,
            List.of(
                row("操作类型", "首次设置", false),
                row("时间", nowStr(), false)),
            null, null, null));
    }

    public void notifyChangeTradePassword(String userId) {
        send(new PlatformWalletNoticeRequest(
            userId, "changeTradePassword", "资金密码修改成功", props.serviceName(), "成功",
            null,
            List.of(
                row("操作类型", "修改密码", false),
                row("时间", nowStr(), false)),
            null, null, null));
    }

    public void notifyRedPacketRefund(WalletRedPacket packet, long refundAmount) {
        String orderId = "RP" + packet.getId();
        String amount = packet.getCurrency() == com.chat99.server.wallet.WalletCurrency.USDT
            ? formatUsdt(refundAmount) + " USDT"
            : formatPlatform(refundAmount) + " 99";
        send(new PlatformWalletNoticeRequest(
            packet.getSenderUserId(), "redPacketRefund", "红包退回", props.serviceName(), "已退回",
            "红包未领完部分已退回您的钱包。",
            List.of(
                row("退回金额", amount, true),
                row("原订单", orderId, false),
                row("时间", nowStr(), false)),
            "查看详情", null, orderId));
    }

    private Map<String, Object> toImData(PlatformWalletNoticeRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customType", CUSTOM_TYPE);
        data.put("businessID", CUSTOM_TYPE);
        data.put("version", 1);
        if (req.noticeType() != null && !req.noticeType().isBlank()) {
            data.put("noticeType", req.noticeType());
        }
        data.put("title", req.title());
        String svc = req.serviceName() != null && !req.serviceName().isBlank()
            ? req.serviceName() : props.serviceName();
        data.put("serviceName", svc);
        if (req.statusLabel() != null && !req.statusLabel().isBlank()) {
            data.put("statusLabel", req.statusLabel());
        }
        if (req.summary() != null && !req.summary().isBlank()) {
            data.put("summary", req.summary());
        }
        if (req.orderId() != null && !req.orderId().isBlank()) {
            data.put("orderId", req.orderId());
        }
        if (req.rows() != null && !req.rows().isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PlatformWalletNoticeRow r : req.rows()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", r.label());
                row.put("value", r.value());
                if (Boolean.TRUE.equals(r.emphasize())) {
                    row.put("emphasize", true);
                }
                rows.add(row);
            }
            data.put("rows", rows);
        }
        if (req.actionLabel() != null && !req.actionLabel().isBlank()) {
            data.put("actionLabel", req.actionLabel());
        }
        if (req.actionUrl() != null && !req.actionUrl().isBlank()) {
            data.put("actionUrl", req.actionUrl());
        }
        return data;
    }

    private void addFriendWithRetry(String userId) {
        String senderId = props.senderUserId();
        for (int i = 1; i <= FRIEND_ADD_ATTEMPTS; i++) {
            try {
                userFriendService.bindMutualFriends(senderId, userId, false);
                log.info("platform wallet friend_bind ok from={} to={}", senderId, userId);
                return;
            } catch (Exception e) {
                log.warn("platform wallet friend_bind attempt {}/{} from={} to={} err={}",
                    i, FRIEND_ADD_ATTEMPTS, senderId, userId, e.getMessage());
            }
            if (i < FRIEND_ADD_ATTEMPTS) {
                sleepQuiet(FRIEND_ADD_RETRY_MS);
            }
        }
    }

    private static PlatformWalletNoticeRow row(String label, String value, boolean emphasize) {
        return new PlatformWalletNoticeRow(label, value, emphasize);
    }

    private static String nowStr() {
        return TIME_FMT.format(Instant.now());
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String formatUsdt(long amountMicro) {
        long whole = amountMicro / 1_000_000L;
        long frac = Math.abs(amountMicro % 1_000_000L);
        if (frac == 0) {
            return Long.toString(whole);
        }
        String fracStr = Long.toString(frac);
        while (fracStr.length() < 6) {
            fracStr = "0" + fracStr;
        }
        fracStr = fracStr.replaceAll("0+$", "");
        return whole + "." + fracStr;
    }

    static String formatPlatform(long fen) {
        long whole = fen / 100L;
        long frac = Math.abs(fen % 100L);
        if (frac == 0) {
            return Long.toString(whole);
        }
        return whole + "." + (frac < 10 ? "0" + frac : Long.toString(frac));
    }

    private static String shortAddress(String addr) {
        if (addr == null || addr.length() <= 12) {
            return addr;
        }
        return addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4);
    }

    private static String shortTx(String tx) {
        if (tx == null || tx.isBlank()) {
            return "-";
        }
        if (tx.length() <= 16) {
            return tx;
        }
        return tx.substring(0, 8) + "…" + tx.substring(tx.length() - 4);
    }
}
