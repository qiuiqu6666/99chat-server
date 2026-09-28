package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.WalletCardNotSubmittedException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WalletCardRecoveryTest extends WalletCardDeliveryTest {
    @Test void missingLocatorIsRecoveredInsteadOfSilentlyDisablingStateUpdates() throws Exception {
        save(row(true));
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, null, null, 0));
        delivery.deliver("rp:1");
        assertThat(read().getState()).isEqualTo("RECONCILING");
        Map<String, Object> message = json.readValue(callback(true, "g1", 12345), Map.class);
        message.put("MsgRandom", 12345);
        when(im.walletCardHistory(anyBoolean(), anyString(), anyString(), anyLong(), any()))
            .thenReturn(Map.of("RspMsgList", List.of(message)));
        due(); delivery.deliver("rp:1");
        assertThat(read().getMessageSeq()).isEqualTo(5L);
        assertThat(read().getState()).isEqualTo("SENT");
    }

    @Test void slowTransportHasBoundedConcurrencyAndExcessWorkStaysDurable() throws Exception {
        for (int i = 1; i <= 5; i++) {
            var order = row(true); order.setId("rp:" + i); order.setClientId("client_" + i); save(order);
        }
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenAnswer(call -> { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return new ImAdminClient.NativeVideoSendResult(true, "key", 1L, 0); });
        try {
            delivery.dispatch();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(repo.findAll().stream().filter(r -> "PENDING".equals(r.getState())).count()).isEqualTo(1);
            release.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (repo.findAll().stream().filter(r -> "SENT".equals(r.getState())).count() < 4 && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertThat(repo.findAll().stream().filter(r -> "SENT".equals(r.getState())).count()).isEqualTo(4);
        } finally { release.countDown(); delivery.shutdown(); }
    }

    @Test void rejectedSendRemainsRetryableBeyondOldDedupWindow() {
        var order = row(true); order.setFirstAttemptAt(Instant.now().minusSeconds(3600)); save(order);
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenThrow(new ImRestException("rejected", 20001))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, "key", 2L, 0));
        delivery.deliver("rp:1");
        assertThat(read().getState()).isEqualTo("PENDING");
        tx.executeWithoutResult(s -> repo.lock("rp:1").orElseThrow().setFirstAttemptAt(Instant.now().minusSeconds(3600)));
        due(); delivery.deliver("rp:1");
        assertThat(read().getState()).isEqualTo("SENT");
    }

    @Test void localConfigurationFailureCannotCreateAmbiguousDelivery() {
        save(row(false));
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenThrow(new WalletCardNotSubmittedException("IM_NOT_CONFIGURED"))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, "key", null, 0));
        delivery.deliver("rp:1"); assertThat(read().getState()).isEqualTo("PENDING");
        due(); delivery.deliver("rp:1"); assertThat(read().getState()).isEqualTo("SENT");
    }

    @Test void restartRecoversOldReviewFromSecondHistoryPage() throws Exception {
        var order = row(false); order.setState("REVIEW"); order.setFirstAttemptAt(Instant.now().minusSeconds(400)); save(order);
        Map<String, Object> message = json.readValue(callback(false, "u2", 12345), Map.class);
        when(im.walletCardHistory(anyBoolean(), anyString(), anyString(), anyLong(), isNull()))
            .thenReturn(Map.of("MsgList", List.of(), "Complete", 0, "LastMsgKey", "next", "LastMsgTime", 123L));
        when(im.walletCardHistory(anyBoolean(), anyString(), anyString(), anyLong(), eq("123|next")))
            .thenReturn(Map.of("MsgList", List.of(message), "Complete", 1));
        delivery.run(); assertThat(read().getReconcileCursor()).isEqualTo("123|next");
        delivery = new WalletCardDelivery(repo, im, json, tx.getTransactionManager());
        due(); delivery.run();
        assertThat(read().getState()).isEqualTo("SENT");
        assertThat(read().getMessageKey()).isEqualTo("receipt-key");
        verify(im, never()).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }

    @Test void failedOrEmptyHistoryNeverAuthorizesAnotherSend() {
        var order = row(false); order.setState("SENDING"); order.setFirstAttemptAt(Instant.now().minusSeconds(400)); save(order);
        when(im.walletCardHistory(anyBoolean(), anyString(), anyString(), anyLong(), any()))
            .thenThrow(new ImRestException("network", 0))
            .thenReturn(Map.of("MsgList", List.of(), "Complete", 1));
        delivery.deliver("rp:1"); due(); delivery.deliver("rp:1");
        assertThat(read().getState()).isEqualTo("RECONCILING");
        verify(im, never()).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }
}
