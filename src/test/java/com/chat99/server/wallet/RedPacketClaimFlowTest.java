package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedPacketClaimFlowTest {

    @Mock WalletRedPacketRepository packetRepository;
    @Mock WalletRedPacketClaimRepository claimRepository;
    @Mock WalletLedgerService ledgerService;
    @Mock PayPinService payPinService;
    @Mock WalletLimitService limitService;
    @Mock WalletFeeService feeService;
    @Mock WalletConfigService configService;
    @Mock UserRepository userRepository;
    @Mock WalletPlatformStatsRepository statsRepository;
    @Mock ImAdminClient imAdminClient;
    @Mock ImUserIdService imUserIdService;
    @Mock GroupAccessService groupAccessService;
    @Mock PlatformWalletNoticeService platformWalletNotice;
    @Mock RedPacketClaimNoticeService claimNoticeService;
    @Mock WalletOrderCardReadCache cardReadCache;
    @Mock RedPacketClaimStateCache claimStateCache;

    RedPacketService service;

    @BeforeEach
    void setup() {
        service = new RedPacketService(
            packetRepository, claimRepository, ledgerService, payPinService, limitService,
            feeService, configService, userRepository, statsRepository, imAdminClient,
            imUserIdService, groupAccessService, platformWalletNotice, claimNoticeService,
            cardReadCache, null, claimStateCache, null, null, null);
        when(imUserIdService.toIm("u1")).thenReturn("u1");
        when(imAdminClient.getRoleInGroup("g1", "u1")).thenReturn("Member");
        when(claimStateCache.available()).thenReturn(true);
    }

    @Test
    void claim_withoutArmedPacket_doesNotTouchDbOrIm() {
        assertThatThrownBy(() -> service.claim("u1", "9"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("RED_PACKET_EXPIRED");
        verify(packetRepository, never()).findById(anyLong());
        verify(packetRepository, never()).findByIdForUpdate(anyLong());
        verify(imAdminClient, never()).getRoleInGroup(any(), any());
        verify(ledgerService, never()).credit(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    private static WalletRedPacket luckyPacket() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setId(9L);
        packet.setSenderUserId("sender");
        packet.setPacketType(RedPacketType.LUCKY_GROUP);
        packet.setConversationType("GROUP");
        packet.setGroupId("g1");
        packet.setCurrency(WalletCurrency.PLATFORM);
        packet.setTotalAmount(2000);
        packet.setPacketCount(13);
        packet.setRemainingAmount(2000);
        packet.setRemainingCount(13);
        packet.setStatus(RedPacketStatus.ACTIVE);
        return packet;
    }
}
