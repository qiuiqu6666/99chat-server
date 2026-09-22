package com.chat99.server.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live Activity {@code content-state} 必须与 Widget {@code ContentState} 字段完全一致：
 * stage / amountText / coin / confirmations / requiredConfirmations / txHashShort。
 */
public final class WithdrawProgressPushPayload {

    public static final String COIN_USDT = "USDT";
    public static final String EVENT_UPDATE = "update";
    public static final String EVENT_END = "end";
    public static final String ANDROID_TYPE = "wallet_withdraw_progress";

    private static final ObjectMapper JSON = new ObjectMapper();

    private WithdrawProgressPushPayload() {}

    public static Map<String, Object> contentState(WalletWithdrawal w, int requiredConfirmations) {
        Map<String, Object> state = new LinkedHashMap<>();
        WithdrawStage stage = WithdrawStage.fromStatus(w == null ? null : w.getStatus());
        state.put("stage", stage.name());
        state.put("amountText", amountText(w == null ? 0 : w.getAmountMicro()));
        state.put("coin", COIN_USDT);
        state.put("confirmations", w == null ? 0 : Math.max(0, w.getConfirmations()));
        state.put("requiredConfirmations", Math.max(1, requiredConfirmations));
        state.put("txHashShort", txHashShort(w == null ? null : w.getTxId()));
        return state;
    }

    public static String apnsPayload(WalletWithdrawal w, int requiredConfirmations, boolean end,
                                     int dismissalSeconds) throws Exception {
        long ts = Instant.now().getEpochSecond();
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("timestamp", ts);
        aps.put("event", end ? EVENT_END : EVENT_UPDATE);
        aps.put("content-state", contentState(w, requiredConfirmations));
        if (end) {
            long dismiss = ts + Math.max(0, dismissalSeconds);
            aps.put("dismissal-date", dismiss);
        }
        return JSON.writeValueAsString(Map.of("aps", aps));
    }

    public static Map<String, String> androidData(WalletWithdrawal w, int requiredConfirmations) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", ANDROID_TYPE);
        data.put("orderId", w == null || w.getId() == null ? "" : String.valueOf(w.getId()));
        data.put("stage", WithdrawStage.fromStatus(w == null ? null : w.getStatus()).name());
        data.put("confirmations", String.valueOf(w == null ? 0 : Math.max(0, w.getConfirmations())));
        data.put("requiredConfirmations", String.valueOf(Math.max(1, requiredConfirmations)));
        return data;
    }

    public static String amountText(long amountMicro) {
        return BigDecimal.valueOf(amountMicro)
            .divide(BigDecimal.valueOf(1_000_000L), 2, RoundingMode.HALF_UP)
            .toPlainString();
    }

    /** 前 6 + 后 4，中间用省略号；过短则原样或空串。 */
    public static String txHashShort(String txId) {
        if (txId == null || txId.isBlank()) {
            return "";
        }
        String t = txId.trim();
        if (t.length() <= 10) {
            return t;
        }
        return t.substring(0, 6) + "\u2026" + t.substring(t.length() - 4);
    }

    public static boolean terminal(WithdrawStage stage) {
        return stage == WithdrawStage.COMPLETED || stage == WithdrawStage.FAILED;
    }
}
