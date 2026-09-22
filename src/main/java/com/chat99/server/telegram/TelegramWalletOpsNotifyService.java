package com.chat99.server.telegram;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.WalletSweepService;
import com.chat99.server.wallet.WalletSweepTrigger;
import com.chat99.server.wallet.WalletWithdrawal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

@Service
public class TelegramWalletOpsNotifyService {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final TelegramOpsProperties props;
    private final TelegramBotClient botClient;
    private final UserRepository userRepository;

    public TelegramWalletOpsNotifyService(TelegramOpsProperties props,
                                          TelegramBotClient botClient,
                                          UserRepository userRepository) {
        this.props = props;
        this.botClient = botClient;
        this.userRepository = userRepository;
    }

    public void notifyDepositCredited(String userId, String tronAddress, long amountMicro, String txId) {
        if (!props.isReady() || !props.notifyDeposit()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>💰 充币入账</b>\n");
        appendUser(sb, userId);
        sb.append("金额: <b>").append(formatUsdt(amountMicro)).append(" USDT</b>\n");
        sb.append("地址: <code>").append(esc(tronAddress)).append("</code>\n");
        sb.append("Tx: <code>").append(esc(txId)).append("</code>\n");
        sb.append("时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    public void notifySweepSuccess(WalletSweepService.SweepResult result,
                                   String collectAddress,
                                   WalletSweepTrigger trigger,
                                   String operator) {
        if (!props.isReady() || !props.notifySweep() || result == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>📥 归集成功</b>\n");
        appendUser(sb, result.userId());
        if (result.usdtSweptMicro() > 0) {
            sb.append("USDT: <b>").append(formatUsdt(result.usdtSweptMicro())).append("</b>\n");
        }
        if (result.trxSweptSun() > 0) {
            sb.append("TRX: ").append(formatTrx(result.trxSweptSun())).append("\n");
        }
        sb.append("从: <code>").append(esc(result.fromAddress())).append("</code>\n");
        sb.append("到: <code>").append(esc(collectAddress)).append("</code>\n");
        if (result.usdtTxId() != null && !result.usdtTxId().isBlank()) {
            sb.append("USDT Tx: <code>").append(esc(result.usdtTxId())).append("</code>\n");
        }
        if (result.trxTxId() != null && !result.trxTxId().isBlank()) {
            sb.append("TRX Tx: <code>").append(esc(result.trxTxId())).append("</code>\n");
        }
        sb.append("方式: ").append(esc(trigger == null ? "AUTO" : trigger.name()));
        if (operator != null && !operator.isBlank()) {
            sb.append(" / ").append(esc(operator));
        }
        sb.append("\n时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    public void notifyWithdrawRequested(WalletWithdrawal w) {
        if (!props.isReady() || !props.notifyWithdraw() || w == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>🏦 提现申请</b>\n");
        appendWithdrawCommon(sb, w);
        sb.append("状态: ").append(esc(w.getStatus() == null ? "PENDING" : w.getStatus().name())).append("\n");
        sb.append("时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    /** 后台审核通过（尚未或即将链上打款）。 */
    public void notifyWithdrawApproved(WalletWithdrawal w) {
        if (!props.isReady() || !props.notifyWithdraw() || w == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>✅ 提现审核通过</b>\n");
        appendWithdrawCommon(sb, w);
        sb.append("时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    /** 链上打款成功。 */
    public void notifyWithdrawPaid(WalletWithdrawal w) {
        if (!props.isReady() || !props.notifyWithdraw() || w == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>💸 提现打款成功</b>\n");
        appendWithdrawCommon(sb, w);
        if (w.getTxId() != null && !w.getTxId().isBlank()) {
            sb.append("Tx: <code>").append(esc(w.getTxId())).append("</code>\n");
        }
        sb.append("时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    /** 后台审核拒绝。 */
    public void notifyWithdrawRejected(WalletWithdrawal w, String reason) {
        if (!props.isReady() || !props.notifyWithdraw() || w == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>🚫 提现审核拒绝</b>\n");
        appendWithdrawCommon(sb, w);
        String fail = reason != null && !reason.isBlank()
            ? reason
            : (w.getFailReason() == null ? "管理员拒绝" : w.getFailReason());
        sb.append("拒绝原因: ").append(esc(fail)).append("\n");
        sb.append("时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    /** 审核通过后链上打款失败。 */
    public void notifyWithdrawPayoutFailed(WalletWithdrawal w, String reason) {
        if (!props.isReady() || !props.notifyWithdraw() || w == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<b>⚠️ 提现打款失败</b>\n");
        appendWithdrawCommon(sb, w);
        String fail = reason != null && !reason.isBlank()
            ? reason
            : (w.getFailReason() == null ? "打款失败" : w.getFailReason());
        sb.append("失败原因: ").append(esc(fail)).append("\n");
        sb.append("时间: ").append(nowStr());
        botClient.sendHtmlAsync(sb.toString());
    }

    private void appendWithdrawCommon(StringBuilder sb, WalletWithdrawal w) {
        sb.append("订单: <code>").append(w.getId()).append("</code>\n");
        appendUser(sb, w.getUserId());
        sb.append("金额: <b>").append(formatUsdt(w.getAmountMicro())).append(" USDT</b>\n");
        sb.append("手续费: ").append(formatUsdt(w.getFeeMicro())).append(" USDT\n");
        sb.append("到账地址: <code>").append(esc(w.getToAddress())).append("</code>\n");
    }

    private void appendUser(StringBuilder sb, String userId) {
        String nickname = resolveNickname(userId);
        if (nickname != null && !nickname.isBlank()) {
            sb.append("昵称: ").append(esc(nickname)).append("\n");
        }
        sb.append("用户: <code>").append(esc(userId)).append("</code>\n");
    }

    private String resolveNickname(String userId) {
        if (userId == null || userId.isBlank() || userRepository == null) {
            return null;
        }
        try {
            return userRepository.findByUserId(userId)
                .map(User::getNickname)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    static String formatUsdt(long micro) {
        return BigDecimal.valueOf(micro)
            .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
            .toPlainString();
    }

    static String formatTrx(long sun) {
        return BigDecimal.valueOf(sun)
            .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
            .toPlainString();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static String nowStr() {
        return TIME_FMT.format(Instant.now());
    }
}
