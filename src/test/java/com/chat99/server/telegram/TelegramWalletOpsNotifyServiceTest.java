package com.chat99.server.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.WalletWithdrawal;
import com.chat99.server.wallet.WithdrawalStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TelegramWalletOpsNotifyServiceTest {

    @Mock TelegramBotClient botClient;
    @Mock UserRepository userRepository;

    TelegramWalletOpsNotifyService service;

    @BeforeEach
    void setup() {
        TelegramOpsProperties props = new TelegramOpsProperties(
            true, "token", "-100123", true, true, true, true, 25, "", false);
        service = new TelegramWalletOpsNotifyService(props, botClient, userRepository);
        User u = new User();
        u.setUserId("user01");
        u.setNickname("小明");
        when(userRepository.findByUserId("user01")).thenReturn(Optional.of(u));
    }

    @Test
    void formatUsdt_andEsc() {
        assertThat(TelegramWalletOpsNotifyService.formatUsdt(1_500_000L)).isEqualTo("1.500000");
        assertThat(TelegramWalletOpsNotifyService.esc("a<b>&c")).isEqualTo("a&lt;b&gt;&amp;c");
    }

    @Test
    void notifyDepositCredited_includesNickname() {
        service.notifyDepositCredited("user01", "TADDR", 2_000_000L, "txid123");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(botClient).sendHtmlAsync(captor.capture());
        String html = captor.getValue();
        assertThat(html).contains("充币入账");
        assertThat(html).contains("2.000000");
        assertThat(html).contains("小明");
        assertThat(html).contains("user01");
    }

    @Test
    void notifySweepSuccess_includesCollectAddressAndTx() {
        var result = new com.chat99.server.wallet.WalletSweepService.SweepResult(
            "user01", "TFROM", 2_000_000L, 0L, "usdt-tx", null);
        service.notifySweepSuccess(result, "TMXDb1uXBx2rNTDrbMGCBorD51uVPhRtvj",
            com.chat99.server.wallet.WalletSweepTrigger.AUTO, "system");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(botClient).sendHtmlAsync(captor.capture());
        String html = captor.getValue();
        assertThat(html).contains("归集成功");
        assertThat(html).contains("2.000000");
        assertThat(html).contains("TMXDb1uXBx2rNTDrbMGCBorD51uVPhRtvj");
        assertThat(html).contains("usdt-tx");
        assertThat(html).contains("小明");
    }

    @Test
    void notifyWithdrawRequested_includesNickname() {
        WalletWithdrawal w = sampleWithdraw(9L, WithdrawalStatus.PENDING);
        service.notifyWithdrawRequested(w);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(botClient).sendHtmlAsync(captor.capture());
        String html = captor.getValue();
        assertThat(html).contains("提现申请");
        assertThat(html).contains("5.000000");
        assertThat(html).contains("小明");
        assertThat(html).contains("user01");
    }

    @Test
    void notifyWithdrawApproved_andPaid_andRejected_sendsHtml() {
        WalletWithdrawal w = sampleWithdraw(11L, WithdrawalStatus.PENDING);
        service.notifyWithdrawApproved(w);
        verify(botClient).sendHtmlAsync(contains("提现审核通过"));
        verify(botClient).sendHtmlAsync(contains("小明"));

        w.setStatus(WithdrawalStatus.COMPLETED);
        w.setTxId("txid-paid");
        service.notifyWithdrawPaid(w);
        verify(botClient).sendHtmlAsync(contains("提现打款成功"));
        verify(botClient).sendHtmlAsync(contains("txid-paid"));

        w.setStatus(WithdrawalStatus.FAILED);
        w.setFailReason("地址异常");
        service.notifyWithdrawRejected(w, "地址异常");
        verify(botClient).sendHtmlAsync(contains("提现审核拒绝"));
        verify(botClient).sendHtmlAsync(contains("地址异常"));
    }

    @Test
    void disabled_skipsAll() {
        service = new TelegramWalletOpsNotifyService(
            new TelegramOpsProperties(false, "token", "-100", true, true, true, true, 25, "", false),
            botClient, userRepository);
        service.notifyDepositCredited("u", "T", 1L, "tx");
        verify(botClient, never()).sendHtmlAsync(org.mockito.ArgumentMatchers.anyString());
    }

    private static WalletWithdrawal sampleWithdraw(long id, WithdrawalStatus status) {
        WalletWithdrawal w = new WalletWithdrawal();
        w.setId(id);
        w.setUserId("user01");
        w.setToAddress("TTO");
        w.setAmountMicro(5_000_000L);
        w.setFeeMicro(1_000_000L);
        w.setStatus(status);
        return w;
    }
}
