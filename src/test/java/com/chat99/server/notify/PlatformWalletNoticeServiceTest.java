package com.chat99.server.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.push.PushService;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.wallet.RedPacketStatus;
import com.chat99.server.wallet.RedPacketType;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletRedPacket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformWalletNoticeServiceTest {

    @Mock ImAdminClient imAdmin;
    @Mock ImUserIdService imUserIdService;
    @Mock PushService pushService;
    @Mock UserFriendService userFriendService;

    PlatformWalletNoticeService service;

    @BeforeEach
    void setup() {
        PlatformWalletNoticeProperties props = new PlatformWalletNoticeProperties(
            "99Chat", null, null, "支付助手", false, false, null, true);
        service = new PlatformWalletNoticeService(props, imAdmin, pushService, userFriendService, imUserIdService);
        when(imUserIdService.toIm(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void notifyRedPacketRefund_usesRefundAmountNotZeroedRemaining() {
        WalletRedPacket packet = platformPacket();
        packet.setId(72L);
        packet.setRemainingAmount(0);
        packet.setStatus(RedPacketStatus.REFUNDED);

        service.notifyRedPacketRefund(packet, 8000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(imAdmin).sendCustomC2c(eq("99Chat"), eq("sender01"), dataCaptor.capture(), eq("红包退回"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) dataCaptor.getValue().get("rows");
        assertThat(rows.get(0).get("value")).isEqualTo("80 99");
    }

    @Test
    void notifyRedPacketRefund_usdtRefundAmount() {
        WalletRedPacket packet = platformPacket();
        packet.setId(73L);
        packet.setCurrency(WalletCurrency.USDT);
        packet.setRemainingAmount(0);

        service.notifyRedPacketRefund(packet, 1_500_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(imAdmin).sendCustomC2c(eq("99Chat"), eq("sender01"), dataCaptor.capture(), eq("红包退回"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) dataCaptor.getValue().get("rows");
        assertThat(rows.get(0).get("value")).isEqualTo("1.5 USDT");
        verify(pushService).sendToUser(eq("sender01"), any());
    }

    @Test
    void notifyWithdrawApproved_sendsProcessingNotice() {
        com.chat99.server.wallet.WalletWithdrawal w = new com.chat99.server.wallet.WalletWithdrawal();
        w.setId(21L);
        w.setUserId("sender01");
        w.setToAddress("TXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        w.setAmountMicro(2_000_000L);
        w.setFeeMicro(1_000_000L);

        service.notifyWithdrawApproved(w);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(imAdmin).sendCustomC2c(eq("99Chat"), eq("sender01"), dataCaptor.capture(), eq("提币审核通过"));
        assertThat(dataCaptor.getValue().get("statusLabel")).isEqualTo("处理中");
        assertThat(dataCaptor.getValue().get("title")).isEqualTo("提币审核通过");
        verify(pushService).sendToUser(eq("sender01"), any());
    }

    private static WalletRedPacket platformPacket() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setSenderUserId("sender01");
        packet.setPacketType(RedPacketType.LUCKY_GROUP);
        packet.setConversationType("GROUP");
        packet.setGroupId("@TGS#abc");
        packet.setCurrency(WalletCurrency.PLATFORM);
        packet.setTotalAmount(10000L);
        return packet;
    }
}
