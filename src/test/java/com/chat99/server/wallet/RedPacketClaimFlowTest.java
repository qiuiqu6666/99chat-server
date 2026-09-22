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
            cardReadCache, null, claimStateCache);
        when(imUserIdService.toIm("u1")).thenReturn("u1");
        when(imAdminClient.getRoleInGroup("g1", "u1")).thenReturn("Member");
        when(claimStateCache.available()).thenReturn(true);
    }

    @Test
    void claim_redisAlready_doesNotLockDb() {
        WalletRedPacket packet = luckyPacket();
        when(packetRepository.findById(9L)).thenReturn(Optional.of(packet));
        when(claimStateCache.tryGrab(9L, "u1")).thenReturn(RedPacketClaimStateCache.GrabResult.ALREADY);

        assertThatThrownBy(() -> service.claim("u1", "9"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("ALREADY_CLAIMED");

        verify(packetRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void claim_redisOk_persistsWithRowLockAndTrueRandom() {
        WalletRedPacket packet = luckyPacket();
        when(packetRepository.findById(9L)).thenReturn(Optional.of(packet));
        when(packetRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(packet));
        when(claimStateCache.tryGrab(9L, "u1")).thenReturn(RedPacketClaimStateCache.GrabResult.OK);
        when(claimRepository.existsByPacketIdAndUserId(9L, "u1")).thenReturn(false);
        when(claimRepository.save(any(WalletRedPacketClaim.class))).thenAnswer(inv -> inv.getArgument(0));
        WalletRedPacketClaim saved = new WalletRedPacketClaim();
        saved.setPacketId(9L);
        saved.setUserId("u1");
        saved.setAmount(100);
        when(claimRepository.findByPacketIdAndUserId(9L, "u1")).thenReturn(Optional.of(saved));

        WalletRedPacketClaim out = service.claim("u1", "9");

        assertThat(out.getUserId()).isEqualTo("u1");
        assertThat(packet.getRemainingCount()).isEqualTo(12);
        assertThat(packet.getRemainingAmount()).isLessThan(2000L);
        assertThat(packet.getRemainingAmount()).isGreaterThanOrEqualTo(12L);
        verify(claimStateCache, never()).releaseGrab(eq(9L), eq("u1"));
        verify(ledgerService).credit(eq("u1"), eq(WalletCurrency.PLATFORM), anyLong(),
            eq(WalletLedgerType.RED_PACKET_RECEIVE), eq("RED_PACKET"), eq(9L), eq("sender"), any());
    }

    @Test
    void claim_duplicateInDb_releasesRedisSlot() {
        WalletRedPacket packet = luckyPacket();
        when(packetRepository.findById(9L)).thenReturn(Optional.of(packet));
        when(packetRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(packet));
        when(claimStateCache.tryGrab(9L, "u1")).thenReturn(RedPacketClaimStateCache.GrabResult.OK);
        when(claimRepository.existsByPacketIdAndUserId(9L, "u1")).thenReturn(true);

        assertThatThrownBy(() -> service.claim("u1", "9"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        verify(claimStateCache).releaseGrab(9L, "u1");
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
