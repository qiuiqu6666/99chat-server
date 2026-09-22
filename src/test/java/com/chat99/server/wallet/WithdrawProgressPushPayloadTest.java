package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.chat99.server.push.ApnsLiveActivityPushSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WithdrawProgressPushPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void contentState_matchesWidgetContract_withoutOrderId() throws Exception {
        WalletWithdrawal w = sample(WithdrawalStatus.CONFIRMING, 8,
            "6fb2e6026c2e4d72d5b7af3dd2ad0e91ba2f43987de4f43e621cf3c55a1029af");
        Map<String, Object> state = WithdrawProgressPushPayload.contentState(w, 19);
        assertThat(state.keySet()).containsExactly(
            "stage", "amountText", "coin", "confirmations", "requiredConfirmations", "txHashShort");
        assertThat(state)
            .containsEntry("stage", "CONFIRMING")
            .containsEntry("amountText", "12.00")
            .containsEntry("coin", "USDT")
            .containsEntry("confirmations", 8)
            .containsEntry("requiredConfirmations", 19)
            .containsEntry("txHashShort", "6fb2e6\u202629af");
        assertThat(state).doesNotContainKey("orderId");
    }

    @Test
    void apnsPayload_updateAndEnd() throws Exception {
        WalletWithdrawal w = sample(WithdrawalStatus.CONFIRMING, 8, "abc123def456");
        @SuppressWarnings("unchecked")
        Map<String, Object> update = JSON.readValue(
            WithdrawProgressPushPayload.apnsPayload(w, 19, false, 0), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) update.get("aps");
        assertThat(aps.get("event")).isEqualTo("update");
        assertThat(aps).containsKey("timestamp");
        assertThat(aps).doesNotContainKey("dismissal-date");

        w.setStatus(WithdrawalStatus.COMPLETED);
        @SuppressWarnings("unchecked")
        Map<String, Object> end = JSON.readValue(
            WithdrawProgressPushPayload.apnsPayload(w, 19, true, 0), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> endAps = (Map<String, Object>) end.get("aps");
        assertThat(endAps.get("event")).isEqualTo("end");
        assertThat(endAps).containsKey("dismissal-date");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) endAps.get("content-state");
        assertThat(state.get("stage")).isEqualTo("COMPLETED");
    }

    @Test
    void androidData_usesStringFields() {
        WalletWithdrawal w = sample(WithdrawalStatus.CONFIRMING, 8, "txid");
        w.setId(42L);
        assertThat(WithdrawProgressPushPayload.androidData(w, 19))
            .containsEntry("type", "wallet_withdraw_progress")
            .containsEntry("orderId", "42")
            .containsEntry("stage", "CONFIRMING")
            .containsEntry("confirmations", "8")
            .containsEntry("requiredConfirmations", "19");
    }

    @Test
    void throttle_skipsSameStageWithinWindow_allowsStageChangeAndTerminal() {
        WalletWithdrawLiveActivity row = new WalletWithdrawLiveActivity();
        row.setLastPushStage("CONFIRMING");
        row.setLastPushConfirmations(8);
        row.setLastPushAt(Instant.parse("2026-08-16T15:00:00Z"));
        Instant now = Instant.parse("2026-08-16T15:00:03Z");
        assertThat(WithdrawProgressPushService.shouldPush(
            row, WithdrawStage.CONFIRMING, 9, now, 5000, false)).isFalse();
        Instant later = Instant.parse("2026-08-16T15:00:06Z");
        assertThat(WithdrawProgressPushService.shouldPush(
            row, WithdrawStage.CONFIRMING, 9, later, 5000, false)).isTrue();
        assertThat(WithdrawProgressPushService.shouldPush(
            row, WithdrawStage.COMPLETED, 8, now, 5000, true)).isTrue();
        row.setLastPushStage("BROADCASTING");
        assertThat(WithdrawProgressPushService.shouldPush(
            row, WithdrawStage.CONFIRMING, 0, now, 5000, false)).isTrue();
    }

    @Test
    void liveActivityToken_hexPassthrough_andBase64Decode() {
        String hex = "a".repeat(64);
        assertThat(ApnsLiveActivityPushSender.toApnsDeviceToken(hex)).isEqualTo(hex);
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        String b64 = Base64.getEncoder().encodeToString(raw);
        String decoded = ApnsLiveActivityPushSender.toApnsDeviceToken(b64);
        assertThat(decoded).hasSize(64);
        assertThat(decoded).startsWith("000102");
    }

    @Test
    void jpushDataMessage_hasNoNotification() {
        Map<String, Object> body = com.chat99.server.push.JpushPushSender.buildDataMessageBody(
            List.of("reg-1"), WithdrawProgressPushPayload.androidData(sample(WithdrawalStatus.CONFIRMING, 1, "t"), 19));
        assertThat(body).doesNotContainKey("notification");
        assertThat(body).containsKey("message");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) body.get("message");
        assertThat(message.get("msg_content")).isEqualTo("wallet_withdraw_progress");
    }

    private static WalletWithdrawal sample(WithdrawalStatus status, int conf, String txId) {
        WalletWithdrawal w = new WalletWithdrawal();
        w.setAmountMicro(12_000_000L);
        w.setStatus(status);
        w.setConfirmations(conf);
        w.setTxId(txId);
        return w;
    }
}
