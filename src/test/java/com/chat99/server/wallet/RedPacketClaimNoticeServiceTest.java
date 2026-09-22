package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class RedPacketClaimNoticeServiceTest {

    @Mock ApplicationEventPublisher events;
    @Mock WalletRedPacketClaimNoticeRepository noticeRepository;
    @Mock ImAdminClient imAdminClient;
    @Mock ImUserIdService imUserIdService;

    RedPacketClaimNoticeService service;

    @BeforeEach
    void setup() {
        service = new RedPacketClaimNoticeService(events, noticeRepository, imAdminClient, imUserIdService);
        lenient().when(imUserIdService.toIm(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void scheduleAfterClaim_publishesForGroupLucky() {
        WalletRedPacket packet = groupPacket();
        packet.setId(10L);
        WalletRedPacketClaim claim = claimRow(10L, "claimer1");

        service.scheduleAfterClaim(packet, claim);

        verify(events).publishEvent(new RedPacketClaimNoticeService.ClaimNoticeCommittedEvent(packet, claim));
    }

    @Test
    void sendNotice_targetedGroupMessageToSender() {
        WalletRedPacket packet = groupPacket();
        packet.setId(10L);
        packet.setRemainingCount(0);
        packet.setStatus(RedPacketStatus.COMPLETED);
        WalletRedPacketClaim claim = claimRow(10L, "claimer1");
        claim.setCreatedAt(Instant.ofEpochSecond(1_718_700_000L));

        when(noticeRepository.existsByNoticeId("rpcn_10_claimer1_1718700000")).thenReturn(false);
        when(noticeRepository.existsByPacketIdAndClaimerUserId(10L, "claimer1")).thenReturn(false);
        when(imAdminClient.getPortraitProfiles(java.util.Set.of("claimer1")))
            .thenReturn(Map.of("claimer1", new ImAdminClient.ProfilePortrait("张三", null)));

        service.sendNotice(packet, claim);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(imAdminClient).sendCustomGroup(
            eq("sender01"),
            eq("@TGS#abc"),
            dataCaptor.capture(),
            eq(List.of("sender01")),
            eq("张三领取了你的红包，你的红包已被领完"),
            eq(Math.abs("rpcn_10_claimer1_1718700000".hashCode())));
        assertThat(dataCaptor.getValue().get("businessID")).isEqualTo("red_packet_claim_notice");
        assertThat(dataCaptor.getValue().get("showFinishedSuffix")).isEqualTo(true);
        verify(noticeRepository).save(org.mockito.ArgumentMatchers.any(WalletRedPacketClaimNotice.class));
    }

    @Test
    void sendNotice_skipsDuplicate() {
        WalletRedPacket packet = groupPacket();
        packet.setId(10L);
        WalletRedPacketClaim claim = claimRow(10L, "claimer1");
        claim.setCreatedAt(Instant.ofEpochSecond(100L));

        when(noticeRepository.existsByNoticeId("rpcn_10_claimer1_100")).thenReturn(true);

        service.sendNotice(packet, claim);

        verifyNoInteractions(imAdminClient);
    }

    @Test
    void buildNoticeText_templates() {
        assertThat(RedPacketClaimNoticeService.buildNoticeText("张三", false))
            .isEqualTo("张三领取了你的红包");
        assertThat(RedPacketClaimNoticeService.buildNoticeText("张三", true))
            .isEqualTo("张三领取了你的红包，你的红包已被领完");
        assertThat(RedPacketClaimNoticeService.buildNoticeText(null, false))
            .isEqualTo("好友领取了你的红包");
    }

    private static WalletRedPacket groupPacket() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setSenderUserId("sender01");
        packet.setPacketType(RedPacketType.LUCKY_GROUP);
        packet.setConversationType("GROUP");
        packet.setGroupId("@TGS#abc");
        packet.setCurrency(WalletCurrency.PLATFORM);
        packet.setStatus(RedPacketStatus.ACTIVE);
        return packet;
    }

    private static WalletRedPacketClaim claimRow(long packetId, String userId) {
        WalletRedPacketClaim claim = new WalletRedPacketClaim();
        claim.setPacketId(packetId);
        claim.setUserId(userId);
        claim.setAmount(100);
        return claim;
    }
}
