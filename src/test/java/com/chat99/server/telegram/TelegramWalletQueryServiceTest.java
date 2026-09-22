package com.chat99.server.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.DeviceModelDisplayService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chat99.server.wallet.DepositStatus;
import com.chat99.server.wallet.TronGridClient;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletChainBalanceService;
import com.chat99.server.wallet.WalletDepositRepository;
import com.chat99.server.wallet.WalletWithdrawalRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class TelegramWalletQueryServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserWalletRepository walletRepository;
    @Mock WalletChainBalanceService chainBalanceService;
    @Mock WalletWithdrawalRepository withdrawalRepository;
    @Mock WalletDepositRepository depositRepository;
    @Mock LoginLogRepository loginLogRepository;
    @Mock UserDeviceRepository userDeviceRepository;

    TelegramWalletQueryService service;

    @BeforeEach
    void setup() {
        TelegramOpsProperties props = new TelegramOpsProperties(
            true, "token", "-1004336340388", true, true, true, true, 25, "", false);
        service = new TelegramWalletQueryService(
            props, userRepository, walletRepository, chainBalanceService,
            withdrawalRepository, depositRepository, loginLogRepository, userDeviceRepository,
            new DeviceModelDisplayService(new ObjectMapper()));
    }

    @Test
    void extractUserIds_supportsAtBareCommandAndPhone() {
        assertThat(service.extractUserIds("@rqwm8onw3j")).containsExactly("rqwm8onw3j");
        assertThat(service.extractUserIds("rqwm8onw3j")).containsExactly("rqwm8onw3j");
        assertThat(service.extractUserIds("/bal rqwm8onw3j")).containsExactly("rqwm8onw3j");
        assertThat(service.extractUserIds("/user @rqwm8onw3j acnj6oxey9"))
            .containsExactly("rqwm8onw3j", "acnj6oxey9");

        User byPhone = new User();
        byPhone.setUserId("rqwm8onw3j");
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("+13800138000")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("+8613800138000")).thenReturn(Optional.of(byPhone));
        assertThat(service.extractUserIds("13800138000")).containsExactly("rqwm8onw3j");
    }

    @Test
    void shouldHandleMessage_onlyOpsChat() {
        assertThat(service.shouldHandleMessage("-1004336340388", "rqwm8onw3j", false)).isTrue();
        assertThat(service.shouldHandleMessage("-1004336340388", "rqwm8onw3j", true)).isFalse();
        assertThat(service.shouldHandleMessage("12345", "rqwm8onw3j", false)).isFalse();
    }

    @Test
    void buildReply_includesProfileAndBalances() {
        User user = new User();
        user.setUserId("rqwm8onw3j");
        user.setNickname("秋");
        user.setPhone("+8613800138000");
        user.setPhoneCountry("86");
        user.setStatus(1);
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        user.setLastActiveAt(Instant.parse("2026-07-04T08:00:00Z"));

        UserWallet wallet = new UserWallet();
        wallet.setUserId("rqwm8onw3j");
        wallet.setTronAddress("TADDR123");
        wallet.setBalanceUsdtMicro(1_500_000L);
        wallet.setBalancePlatformFen(128_800L);
        wallet.setChainUsdtMicro(2_000_000L);
        wallet.setChainTrxSun(3_000_000L);
        wallet.setPayPinHash("hash");
        wallet.setPayPinFailCount(0);
        wallet.setChainBalanceAt(Instant.parse("2026-07-04T09:00:00Z"));

        LoginLog login = new LoginLog();
        login.setIp("1.2.3.4");
        login.setClientPlatform("ios");
        login.setClientVersion("1.0.0");
        login.setDeviceId("device-abcdef-1234");
        login.setCreatedAt(Instant.parse("2026-07-04T08:30:00Z"));

        UserDevice device = new UserDevice();
        device.setPlatform("ios");
        device.setModel("iPhone18,2");
        device.setTrusted(true);

        when(userRepository.findByUserId("rqwm8onw3j")).thenReturn(Optional.of(user));
        when(walletRepository.findById("rqwm8onw3j")).thenReturn(Optional.of(wallet));
        when(chainBalanceService.refreshAndSave(eq(wallet)))
            .thenReturn(Optional.of(new TronGridClient.AccountBalances(3_000_000L, 2_000_000L)));
        when(withdrawalRepository.sumPendingAmountMicro(eq("rqwm8onw3j"), any())).thenReturn(0L);
        when(withdrawalRepository.countByUserIdAndStatusIn(eq("rqwm8onw3j"), any())).thenReturn(0L);
        when(depositRepository.countByUserIdAndStatus("rqwm8onw3j", DepositStatus.CONFIRMING)).thenReturn(1L);
        when(loginLogRepository.findFirstByUserIdAndSuccessTrueOrderByCreatedAtDesc("rqwm8onw3j"))
            .thenReturn(Optional.of(login));
        when(loginLogRepository.findDistinctSuccessIpsByUserId("rqwm8onw3j"))
            .thenReturn(List.of("1.2.3.4", "5.6.7.8"));
        when(loginLogRepository.findDistinctDeviceIdsByUserId("rqwm8onw3j"))
            .thenReturn(List.of("device-abcdef-1234", "device-other"));
        when(loginLogRepository.findDistinctSuccessVersionsByUserId("rqwm8onw3j"))
            .thenReturn(List.of("1.0.0", "1.1.0"));
        when(loginLogRepository.findDistinctSuccessPlatformsByUserId("rqwm8onw3j"))
            .thenReturn(List.of("ios", "android"));
        when(loginLogRepository.findByUserIdAndSuccessTrueOrderByCreatedAtDesc(eq("rqwm8onw3j"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(login)));
        when(userDeviceRepository.findByUserIdOrderByLastLoginAtDesc("rqwm8onw3j")).thenReturn(List.of(device));

        String reply = service.buildReply("@rqwm8onw3j");

        assertThat(reply).contains("rqwm8onw3j");
        assertThat(reply).contains("秋");
        assertThat(reply).contains("+8613800138000");
        assertThat(reply).contains("正常");
        assertThat(reply).contains("已设置");
        assertThat(reply).contains("1.500000 USDT");
        assertThat(reply).contains("1288.00 99");
        assertThat(reply).contains("确认中充值: 1 笔");
        assertThat(reply).contains("1.2.3.4");
        assertThat(reply).contains("历史IP数: 2");
        assertThat(reply).contains("历史设备数: 2");
        assertThat(reply).contains("1.0.0");
        assertThat(reply).contains("1.1.0");
        assertThat(reply).contains("iPhone 17 Pro Max");
        assertThat(reply).doesNotContain("iPhone18,2");
        assertThat(reply).contains("信任设备: iOS / iPhone 17 Pro Max（共 1 台）");
        verify(chainBalanceService).refreshAndSave(wallet);
    }

    @Test
    void buildReply_notFound() {
        when(userRepository.findByUserId("rqwm8onw3j")).thenReturn(Optional.empty());
        when(walletRepository.findById("rqwm8onw3j")).thenReturn(Optional.empty());
        assertThat(service.buildReply("rqwm8onw3j")).contains("不存在");
    }
}
