package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RedPacketPublicIdTest {

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

    RedPacketService service;

    @BeforeEach
    void setup() {
        service = new RedPacketService(
            packetRepository, claimRepository, ledgerService, payPinService, limitService,
            feeService, configService, userRepository, statsRepository, imAdminClient,
            imUserIdService, groupAccessService, platformWalletNotice, claimNoticeService,
            cardReadCache, null, null);
    }

    @Test
    void requirePacket_acceptsNumericAndPublicId() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setId(10L);
        packet.setPublicId("red_packet_58b05f2a-875f-4f1d-bd32-7975357efa2a");
        when(packetRepository.findById(10L)).thenReturn(Optional.of(packet));
        when(packetRepository.findByPublicId("red_packet_58b05f2a-875f-4f1d-bd32-7975357efa2a"))
            .thenReturn(Optional.of(packet));

        assertThat(service.requirePacket("10").getId()).isEqualTo(10L);
        assertThat(service.requirePacket("red_packet_58b05f2a-875f-4f1d-bd32-7975357efa2a").getId())
            .isEqualTo(10L);
        assertThat(service.requirePacket("RED_PACKET_58B05F2A-875F-4F1D-BD32-7975357EFA2A").getId())
            .isEqualTo(10L);
    }

    @Test
    void requirePacket_unknownIdIsNotFoundNotInvalid() {
        assertThatThrownBy(() -> service.requirePacket("red_packet_not-a-uuid"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> service.requirePacket("   "))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("NOT_FOUND");
    }
}
